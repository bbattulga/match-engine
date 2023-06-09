package bbattulga.matchengine.libmodel.jpa.repository;

import bbattulga.matchengine.libmodel.consts.OrderSide;
import bbattulga.matchengine.libmodel.consts.OrderStatus;
import bbattulga.matchengine.libmodel.jpa.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    public static interface OrderBookDepth {
        OrderSide getSide();
        BigDecimal getPrice();
        BigDecimal getQty();
    }

    Optional<Order> findByOrderCode(UUID code);

    List<Order> findByStatusInOrderByUtcAsc(List<OrderStatus> status);

    List<Order> findByPairIdAndStatusInOrderByUtcAsc(Long pairId, List<OrderStatus> status);

    @Query(value = "(select\n" +
            "eo.side,\n" +
            "eo.price as \"price\",\n" +
            "sum(eo.remaining_qty) as \"qty\"\n" +
            "from ex_order eo \n" +
            "where eo.pair_id = :pairId and eo.status in ('OPEN', 'PARTIALLY_FILLED')\n" +
            "group by eo.price, eo.side having eo.side = 'SELL'\n" +
            "order by eo.price desc)\n" +
            "union all\n" +
            "(select\n" +
            "eo.side,\n" +
            "eo.price as \"price\",\n" +
            "sum(eo.remaining_qty) as \"qty\"\n" +
            "from ex_order eo \n" +
            "where eo.pair_id = :pairId and eo.status in ('OPEN', 'PARTIALLY_FILLED')\n" +
            "group by eo.price, eo.side having eo.side = 'BUY'\n" +
            "order by eo.price desc)", nativeQuery = true)
    List<OrderBookDepth> findOrderBookByPairId(@Param("pairId") Long pairId);
}
