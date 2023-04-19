package bbattulga.matchengine.servicematchengine.service.queue;

import bbattulga.matchengine.servicematchengine.consts.OrderType;
import bbattulga.matchengine.servicematchengine.dto.engine.CancelOrderEvent;
import bbattulga.matchengine.servicematchengine.dto.engine.OrderEvent;
import bbattulga.matchengine.servicematchengine.dto.request.CancelOrderRequest;
import com.lmax.disruptor.RingBuffer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CancelOrderPlaceService {

    private final RingBuffer<OrderEvent> ringBuffer;

    public void placeCancelOrder(CancelOrderRequest request) {
        long sequenceId = ringBuffer.next();
        final var orderEvent =ringBuffer.get(sequenceId);
        orderEvent.setType(OrderType.CANCEL);
        final var cancelOrderEvent = (CancelOrderEvent) orderEvent;
        cancelOrderEvent.setId(request.getId());
        cancelOrderEvent.setPrice(request.getPrice());
        cancelOrderEvent.setSide(request.getSide());
        ringBuffer.publish(sequenceId);
    }

}
