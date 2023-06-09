package bbattulga.matchengine.servicematchengine;

import bbattulga.matchengine.libmodel.engine.OrderEvent;
import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SequentialExecutionService {

    private final LimitOrderExecutorService limitOrderExecutorService;
    private final CancelOrderExecutorService cancelOrderExecutorService;

    public EventHandler<OrderEvent>[] getEventHandler() {
        final EventHandler<OrderEvent> eventHandler = (orderEvent, sequence, endOfBatch) -> {
            final var nsStart = System.nanoTime();
            switch (orderEvent.getType()) {
                case LIMIT -> limitOrderExecutorService.executeLimitOrder(orderEvent, nsStart, true);
                case CANCEL -> cancelOrderExecutorService.executeCancelOrder(orderEvent, nsStart, true);
                default -> throw new Exception("Invalid order orderEvent type");
            }
        };
        return new EventHandler[] { eventHandler };
    }
}
