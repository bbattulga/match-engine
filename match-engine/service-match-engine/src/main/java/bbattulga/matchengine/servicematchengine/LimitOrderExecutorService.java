package bbattulga.matchengine.servicematchengine;

import bbattulga.matchengine.libmodel.consts.OrderSide;
import bbattulga.matchengine.libmodel.consts.OrderStatus;
import bbattulga.matchengine.libmodel.consts.OrderType;
import bbattulga.matchengine.libmodel.engine.LimitOrderEvent;
import bbattulga.matchengine.libmodel.engine.OrderBookPriceLevel;
import bbattulga.matchengine.libmodel.engine.OrderEvent;
import bbattulga.matchengine.libmodel.engine.output.OrderMatchOutput;
import bbattulga.matchengine.libmodel.engine.output.OrderOpenOutput;
import bbattulga.matchengine.servicematchengine.config.MatchEngineConfig;
import bbattulga.matchengine.servicematchengine.service.place.OrderBookService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class LimitOrderExecutorService {

    private final OrderBookService orderBookService;
    private final SequentialOutputService sequentialOutputService;
    private final MatchEngineConfig config;
    private boolean isPublish = true;

    public void executeLimitOrder(LimitOrderEvent execOrder, long nsStart, boolean isPublish) throws JsonProcessingException {
        this.isPublish = isPublish;
        if (execOrder.getSide() == OrderSide.BUY) {
            execLimitBuy(execOrder, nsStart);
        } else if (execOrder.getSide() == OrderSide.SELL) {
            execLimitSell(execOrder, nsStart);
        }
    }

    private void execLimitBuy(LimitOrderEvent execOrder, long nsStart) throws JsonProcessingException {
        long nsMatchStart = nsStart;
        final var asks = orderBookService.getAsks();
        if (asks.isEmpty()) {
            newRestingBid((OrderEvent) execOrder, OrderStatus.OPEN, nsStart);
            return;
        }
        final var lowestAskPrice = asks.firstKey();
        if (execOrder.getPrice().compareTo(lowestAskPrice) < 0) {
            // no matching price
            newRestingBid((OrderEvent) execOrder, OrderStatus.OPEN, nsStart);
            return;
        }
        final var matchingAsks = asks.subMap(lowestAskPrice, true, execOrder.getPrice(), true);
        if (matchingAsks.isEmpty()) {
            newRestingBid((OrderEvent) execOrder, OrderStatus.OPEN, nsStart);
            return;
        }
        List<BigInteger> removePriceLevel = new ArrayList<>();
        for (final var matchingEntry: matchingAsks.entrySet()) {
            final var matchingPrice = matchingEntry.getKey();
            final var level = matchingEntry.getValue();
            final var restingOrders = level.getOrders();
            int orderIndex = 0;
            while (execOrder.getRemainingQty().compareTo(BigInteger.ZERO) > 0 && !restingOrders.isEmpty()) {
                final var restingOrderOriginal = restingOrders.get(orderIndex);
                if (restingOrderOriginal.getType() == OrderType.LIMIT) {
                    final var restingOrder = restingOrderOriginal.clone();
                    final var matchQty = execOrder.getRemainingQty().min(restingOrder.getRemainingQty());
                    final var matchTotal = matchingPrice.multiply(matchQty).divide(BigInteger.valueOf(config.getBaseTick()));
                    final var takerFeeQty = matchQty.multiply(config.getTakerFee()).divide(BigInteger.valueOf(100));
                    final var restingRemainingQty = restingOrder.getRemainingQty().subtract(matchQty);
                    final var restingRemainingTotal = matchingPrice.multiply(restingRemainingQty).divide(BigInteger.valueOf(config.getBaseTick()));
                    restingOrder.setRemainingQty(restingRemainingQty);
                    restingOrder.setRemainingTotal(restingRemainingTotal);
                    restingOrder.setFillQty(restingOrder.getFillQty().add(matchQty));
                    restingOrder.setFillTotal(restingOrder.getFillTotal().add(matchTotal));
                    final var execRemainingQty = execOrder.getRemainingQty().subtract(matchQty);
                    final var execRemainingTotal = matchingPrice.multiply(execRemainingQty).divide(BigInteger.valueOf(config.getBaseTick()));
                    execOrder.setRemainingQty(execRemainingQty);
                    execOrder.setRemainingTotal(execRemainingTotal);
                    execOrder.setExecQty(execOrder.getExecQty().add(matchQty));
                    execOrder.setExecTotal(execOrder.getExecTotal().add(matchTotal));
                    if (restingOrder.getRemainingQty().compareTo(BigInteger.ZERO) == 0) {
                        // resting order fulfilled
                        restingOrder.setStatus(OrderStatus.FULFILLED);
                        restingOrders.remove(orderIndex);
                    } else {
                        restingOrder.setStatus(OrderStatus.PARTIALLY_FILLED);
                        restingOrders.set(orderIndex, restingOrder);
                    }
                    boolean isExecOrderFulfilled = execOrder.getRemainingQty().compareTo(BigInteger.ZERO) == 0;
                    if (isExecOrderFulfilled) {
                        execOrder.setStatus(OrderStatus.FULFILLED);
                    } else {
                        execOrder.setStatus(OrderStatus.PARTIALLY_FILLED);
                    }
                    final var makerFeeQty = matchTotal.multiply(config.getMakerFee()).divide(BigInteger.valueOf(100));
                    if (isPublish) {
                        final var matchNs = System.nanoTime() - nsMatchStart;
                        final var matchOutput = OrderMatchOutput.builder()
                                .base(config.getBase())
                                .quote(config.getQuote())
                                .makerFee(makerFeeQty)
                                .takerFee(takerFeeQty)
                                .price(matchingPrice)
                                .qty(matchQty)
                                .total(matchTotal)
                                .execOrder((OrderEvent) execOrder)
                                .remainingOrder(restingOrder)
                                .utc(Instant.now().toEpochMilli())
                                .ns(matchNs)
                                .build();
                        sequentialOutputService.publish(matchOutput);
                    }
                    if (isExecOrderFulfilled) {
                        // exec order fulfilled
                        break;
                    }
                }
                nsMatchStart = System.nanoTime();
            }
            if (restingOrders.isEmpty()) {
                removePriceLevel.add(matchingPrice); // current price level orders fulfilled
            }
        }
        for (final var rp: removePriceLevel) {
            asks.remove(rp);
        }
        final var nsMatchEnd = System.nanoTime();
        if (execOrder.getRemainingQty().compareTo(BigInteger.ZERO) > 0) {
            // exec order not fulfilled, save as resting order
            newRestingBid((OrderEvent) execOrder, OrderStatus.PARTIALLY_FILLED, nsMatchEnd);
        }
    }

    private void execLimitSell(LimitOrderEvent execOrder, long nsStart) throws JsonProcessingException {
        long nsMatchStart = nsStart;
        final var bids = orderBookService.getBids();
        if (bids.isEmpty()) {
            newRestingAsk((OrderEvent) execOrder, OrderStatus.OPEN, nsStart);
            return;
        }
        final var highestBidPrice = bids.lastKey();
        if (highestBidPrice.compareTo(execOrder.getPrice()) < 0) {
            // no matching price
            newRestingAsk((OrderEvent) execOrder, OrderStatus.OPEN, nsStart);
            return;
        }
        final var matchingBids = bids.subMap(execOrder.getPrice(), true, highestBidPrice, true);
        if (matchingBids.isEmpty()) {
            newRestingAsk((OrderEvent) execOrder, OrderStatus.OPEN, nsStart);
            return;
        }
        final var matchingSet = matchingBids.descendingKeySet();
        List<BigInteger> removePriceLevel = new ArrayList<>() ;
        final var matchingPrice = execOrder.getPrice();
        for (final var matchingBidPrice: matchingSet) {
            final var level = bids.get(matchingBidPrice);
            final var restingOrders = level.getOrders();
            int orderIndex = 0;
            while (execOrder.getRemainingQty().compareTo(BigInteger.ZERO) > 0
                    && !restingOrders.isEmpty()) {
                final var restingOrderOriginal = restingOrders.get(orderIndex);
                if (restingOrderOriginal.getType() == OrderType.LIMIT) {
                    final var restingOrder = restingOrderOriginal.clone();
                    final var matchQty = execOrder.getRemainingQty().min(restingOrder.getRemainingQty());
                    final var makerFeeQty = matchQty.multiply(config.getMakerFee()).divide(BigInteger.valueOf(100));
                    final var matchTotal = matchingPrice.multiply(matchQty).divide(BigInteger.valueOf(config.getBaseTick()));
                    final var restingRemainingQty = restingOrder.getRemainingQty().subtract(matchQty);
                    final var restingRemainingTotal = matchingPrice.multiply(restingRemainingQty).divide(BigInteger.valueOf(config.getBaseTick()));
                    restingOrder.setRemainingQty(restingRemainingQty);
                    restingOrder.setRemainingTotal(restingRemainingTotal);
                    restingOrder.setFillQty(restingOrder.getFillQty().add(matchQty));
                    restingOrder.setFillTotal(restingOrder.getFillTotal().add(matchTotal));
                    final var execRemainingQty = execOrder.getRemainingQty().subtract(matchQty);
                    final var execRemainingTotal = matchingPrice.multiply(execRemainingQty).divide(BigInteger.valueOf(config.getBaseTick()));
                    execOrder.setRemainingQty(execRemainingQty);
                    execOrder.setRemainingTotal(execRemainingTotal);
                    execOrder.setExecQty(execOrder.getExecQty().add(matchQty));
                    execOrder.setExecTotal(execOrder.getExecTotal().add(matchTotal));
                    if (restingOrder.getRemainingQty().compareTo(BigInteger.ZERO) == 0) {
                        // resting order fulfilled
                        restingOrder.setStatus(OrderStatus.FULFILLED);
                        restingOrders.remove(orderIndex);
                    } else {
                        restingOrder.setStatus(OrderStatus.PARTIALLY_FILLED);
                        restingOrders.set(orderIndex, restingOrder);
                    }
                    boolean isExecOrderFulfilled = execOrder.getRemainingQty().compareTo(BigInteger.ZERO) == 0;
                    if (isExecOrderFulfilled) {
                        execOrder.setStatus(OrderStatus.FULFILLED);
                    } else {
                        execOrder.setStatus(OrderStatus.PARTIALLY_FILLED);
                    }
                    final var takerFee = matchTotal.multiply(config.getTakerFee()).divide(BigInteger.valueOf(100));
                    if (isPublish) {
                        final var matchNs = System.nanoTime() - nsMatchStart;
                        final var matchOutput = OrderMatchOutput.builder()
                                .base(config.getBase())
                                .quote(config.getQuote())
                                .makerFee(makerFeeQty)
                                .takerFee(takerFee)
                                .price(matchingPrice)
                                .total(matchTotal)
                                .qty(matchQty)
                                .execOrder((OrderEvent) execOrder)
                                .remainingOrder(restingOrder)
                                .utc(Instant.now().toEpochMilli())
                                .ns(matchNs)
                                .build();
                        sequentialOutputService.publish(matchOutput);
                    }
                    if (isExecOrderFulfilled) {
                        // exec order fulfilled
                        break;
                    }
                }
                nsMatchStart = System.nanoTime();
            }
            if (restingOrders.isEmpty()) {
                removePriceLevel.add(matchingBidPrice); // current price level orders fulfilled
            }
        }
        for (final var rp: removePriceLevel) {
            bids.remove(rp);
        }
        final var nsMatchEnd = System.nanoTime();
        if (execOrder.getRemainingQty().compareTo(BigInteger.ZERO) > 0) {
            // exec order not fulfilled, save as resting order
            newRestingAsk((OrderEvent) execOrder, OrderStatus.PARTIALLY_FILLED, nsMatchEnd);
        }
    }


    private void newRestingBid(OrderEvent bid, OrderStatus status, long nsStart) throws JsonProcessingException {
        final var bids = orderBookService.getBids();
        final var priceLevel = bids.getOrDefault(bid.getPrice(), new OrderBookPriceLevel());
        priceLevel.setPrice(bid.getPrice());
        priceLevel.getOrders().add(bid);
        bid.setStatus(status);
        bids.put(bid.getPrice(), priceLevel);
        final var ns = System.nanoTime() - nsStart;
        if (isPublish) {
            publishOrderOpen(bid, ns);
        }
    }

    private void newRestingAsk(OrderEvent ask, OrderStatus status, long nsStart) throws JsonProcessingException {
        final var asks = orderBookService.getAsks();
        final var priceLevel = asks.getOrDefault(ask.getPrice(), new OrderBookPriceLevel());
        priceLevel.setPrice(ask.getPrice());
        priceLevel.getOrders().add(ask);
        ask.setStatus(status);
        asks.put(ask.getPrice(), priceLevel);
        final var ns = System.nanoTime() - nsStart;
        if (isPublish) {
            publishOrderOpen(ask, ns);
        }
    }

    private void publishOrderOpen(OrderEvent order, long ns) throws JsonProcessingException {
        final var openOutput = OrderOpenOutput.builder()
                .orderId(order.getId())
                .uid(order.getUid())
                .type(order.getType())
                .base(config.getBase())
                .quote(config.getQuote())
                .price(order.getPrice())
                .side(order.getSide())
                .qty(order.getQty())
                .total(order.getTotal())
                .execQty(order.getExecQty())
                .execTotal(order.getExecTotal())
                .remainingQty(order.getRemainingQty())
                .remainingTotal(order.getRemainingTotal())
                .execUtc(Instant.now().toEpochMilli())
                .ns(ns)
                .utc(order.getUtc())
                .build();
        sequentialOutputService.publish(openOutput);
    }

}
