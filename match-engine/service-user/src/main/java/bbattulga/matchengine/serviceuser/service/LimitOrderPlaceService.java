package bbattulga.matchengine.serviceuser.service;

import bbattulga.matchengine.libmodel.consts.OrderSide;
import bbattulga.matchengine.libmodel.consts.OrderStatus;
import bbattulga.matchengine.libmodel.consts.OrderType;
import bbattulga.matchengine.libmodel.engine.http.client.MatchEngineClient;
import bbattulga.matchengine.libmodel.engine.http.request.LimitOrderRequest;
import bbattulga.matchengine.libmodel.exception.BadParameterException;
import bbattulga.matchengine.libmodel.exception.ServiceUnavailableException;
import bbattulga.matchengine.libmodel.jpa.entity.Asset;
import bbattulga.matchengine.libmodel.jpa.entity.Order;
import bbattulga.matchengine.libmodel.jpa.entity.Pair;
import bbattulga.matchengine.libmodel.jpa.repository.AssetRepository;
import bbattulga.matchengine.libmodel.jpa.repository.PairRepository;
import bbattulga.matchengine.serviceuser.dto.request.OrderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LimitOrderPlaceService {

    private final MatchEngineClient matchEngineClient;
    private final PairRepository pairRepository;
    private final AssetRepository assetRepository;
    private final LimitOrderSavePendingService limitOrderSavePendingService;

    @Transactional
    public Order placeOrder(OrderRequest request) {
        checkRequest(request);
        final var nowUtc = Instant.now().toEpochMilli();
        final var pair = getPair(request);
        final var base = getBase(pair);
        final var quote = getQuote(pair);
        final var price = getPrice(request, quote);
        final var qty = getQty(request, base);
        final var total = getTotal(request, quote);
        checkTotal(request);
        final var orderCode = UUID.randomUUID();
        final var uid = UUID.fromString(request.getUid());
        final var order = new Order();
        order.setUid(uid);
        order.setOrderCode(orderCode);
        order.setPairId(pair.getPairId());
        order.setStatus(OrderStatus.PRE_OPEN);
        order.setType(OrderType.LIMIT);
        order.setSide(request.getSide());
        order.setPrice(price);
        order.setQty(qty);
        order.setTotal(total);
        order.setUtc(nowUtc);
        order.setExecQty(BigInteger.ZERO);
        order.setFillQty(BigInteger.ZERO);
        order.setRemainingQty(qty);
        order.setRemainingTotal(total);
        order.setCreatedAt(LocalDateTime.now());
        limitOrderSavePendingService.savePendingInNewTransaction(order);
        try {
            final var pairSymbol = String.format("%s-%s", base.getSymbol(), quote.getSymbol());
            callMatchEngineLimitOrder(uid, pairSymbol, order);
        } catch (Exception e) {
            log.error("{}", e.getMessage());
            throw new ServiceUnavailableException("match-engine-unavailable");
        }
        return order;
    }

    private void callMatchEngineLimitOrder(UUID uid, String symbol, Order order) {
        final var engineRequest = LimitOrderRequest.builder()
                .id(order.getOrderCode().toString())
                .uid(uid.toString())
                .side(order.getSide())
                .price(order.getPrice())
                .qty(order.getQty())
                .total(order.getTotal())
                .utc(order.getUtc())
                .build();
        matchEngineClient.limitOrder(symbol, engineRequest);
    }

    private Pair getPair(OrderRequest request) {
        return pairRepository.findBySymbolAndStatus(request.getSymbol(), Pair.Status.ACTIVE).orElseThrow(() -> new BadParameterException("pair-not-found"));
    }

    private Asset getBase(Pair pair) {
        return assetRepository.findByAssetIdAndStatus(pair.getBaseAssetId(), Asset.Status.ACTIVE).orElseThrow(() -> new BadParameterException("base-asset-not-found"));
    }

    private Asset getQuote(Pair pair) {
        return assetRepository.findByAssetIdAndStatus(pair.getQuoteAssetId(), Asset.Status.ACTIVE).orElseThrow(() -> new BadParameterException("quote-asset-not-found"));
    }

    private BigInteger getPrice(OrderRequest request, Asset asset) {
        final var bigPrice = BigDecimal.valueOf(request.getPrice()).multiply(BigDecimal.TEN.pow(asset.getScale().intValue()));
        try {
            return bigPrice.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new BadParameterException("price-overflow");
        }
    }

    private BigInteger getTotal(OrderRequest request, Asset asset) {
        final var bigTotal = BigDecimal.valueOf(request.getTotal()).multiply(BigDecimal.TEN.pow(asset.getScale().intValue()));
        try {
            return bigTotal.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new BadParameterException("total-overflow");
        }
    }

    private BigInteger getQty(OrderRequest request, Asset asset) {
        final var bigQty = BigDecimal.valueOf(request.getQty()).multiply(BigDecimal.TEN.pow(asset.getScale().intValue()));
        try {
            return bigQty.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new BadParameterException("qty-overflow");
        }
    }

    private void checkTotal(OrderRequest request) {
        final var price = BigDecimal.valueOf(request.getPrice());
        final var qty = BigDecimal.valueOf(request.getQty());
        final var requestTotal = BigDecimal.valueOf(request.getTotal());
        final var computedTotal = price.multiply(qty);
        if (computedTotal.compareTo(requestTotal) != 0) {
            throw new BadParameterException("total-invalid");
        }
    }

    private void checkRequest(OrderRequest request) {
        if (request.getPrice() <= 0) {
            throw new BadParameterException("invalid-price");
        }
        if (request.getQty() <= 0) {
            throw new BadParameterException("invalid-qty");
        }
        if (request.getTotal() <= 0) {
            throw new BadParameterException("invalid-total");
        }
        if (!(request.getSide().equals(OrderSide.BUY) || request.getSide().equals(OrderSide.SELL))) {
            throw new BadParameterException("invalid-side");
        }
    }
}
