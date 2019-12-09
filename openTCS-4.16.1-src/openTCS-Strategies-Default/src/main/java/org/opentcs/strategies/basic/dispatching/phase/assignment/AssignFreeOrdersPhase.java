/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.strategies.basic.dispatching.phase.assignment;

import org.opentcs.components.kernel.Router;
import org.opentcs.components.kernel.services.TCSObjectService;
import org.opentcs.data.ObjectHistory;
import org.opentcs.data.model.Point;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.strategies.basic.dispatching.AssignmentCandidate;
import org.opentcs.strategies.basic.dispatching.OrderReservationPool;
import org.opentcs.strategies.basic.dispatching.Phase;
import org.opentcs.strategies.basic.dispatching.TransportOrderUtil;
import org.opentcs.strategies.basic.dispatching.phase.AssignmentState;
import org.opentcs.strategies.basic.dispatching.phase.CandidateFilterResult;
import org.opentcs.strategies.basic.dispatching.phase.OrderFilterResult;
import org.opentcs.strategies.basic.dispatching.phase.VehicleFilterResult;
import org.opentcs.strategies.basic.dispatching.priorization.CompositeOrderCandidateComparator;
import org.opentcs.strategies.basic.dispatching.priorization.CompositeOrderComparator;
import org.opentcs.strategies.basic.dispatching.priorization.CompositeVehicleCandidateComparator;
import org.opentcs.strategies.basic.dispatching.priorization.CompositeVehicleComparator;
import org.opentcs.strategies.basic.dispatching.selection.candidates.CompositeAssignmentCandidateSelectionFilter;
import org.opentcs.strategies.basic.dispatching.selection.orders.CompositeTransportOrderSelectionFilter;
import org.opentcs.strategies.basic.dispatching.selection.orders.IsFreelyDispatchableToAnyVehicle;
import org.opentcs.strategies.basic.dispatching.selection.vehicles.CompositeVehicleSelectionFilter;
import org.opentcs.strategies.basic.dispatching.selection.vehicles.IsAvailableForAnyOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.opentcs.data.order.TransportOrderHistoryCodes.*;

/**
 * Assigns transport orders to vehicles that are currently not processing any and are not bound to
 * any order sequences.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class AssignFreeOrdersPhase
        implements Phase {

    /**
     * This class's Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AssignFreeOrdersPhase.class);
    /**
     * The object service
     */
    private final TCSObjectService objectService;
    /**
     * The Router instance calculating route costs.
     */
    private final Router router;
    /**
     * Stores reservations of orders for vehicles.
     */
    private final OrderReservationPool orderReservationPool;
    /**
     * Defines the order of vehicles when there are less vehicles than transport orders.
     */
    private final Comparator<Vehicle> vehicleComparator;
    /**
     * Defines the order of transport orders when there are less transport orders than vehicles.
     */
    private final Comparator<TransportOrder> orderComparator;
    /**
     * Sorts candidates when looking for a transport order to be assigned to a vehicle.
     */
    private final Comparator<AssignmentCandidate> orderCandidateComparator;
    /**
     * Sorts candidates when looking for a vehicle to be assigned to a transport order.
     */
    private final Comparator<AssignmentCandidate> vehicleCandidateComparator;
    /**
     * A collection of predicates for filtering vehicles.
     */
    private final CompositeVehicleSelectionFilter vehicleSelectionFilter;

    private final IsAvailableForAnyOrder isAvailableForAnyOrder;

    private final IsFreelyDispatchableToAnyVehicle isFreelyDispatchableToAnyVehicle;
    /**
     * A collection of predicates for filtering transport orders.
     */
    private final CompositeTransportOrderSelectionFilter transportOrderSelectionFilter;
    /**
     * A collection of predicates for filtering assignment candidates.
     */
    private final CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter;

    private final TransportOrderUtil transportOrderUtil;

    /**
     * Indicates whether this component is initialized.
     */
    private boolean initialized;

    @Inject
    public AssignFreeOrdersPhase(
            TCSObjectService objectService,
            Router router,
            OrderReservationPool orderReservationPool,
            CompositeVehicleComparator vehicleComparator,
            CompositeOrderComparator orderComparator,
            CompositeOrderCandidateComparator orderCandidateComparator,
            CompositeVehicleCandidateComparator vehicleCandidateComparator,
            CompositeVehicleSelectionFilter vehicleSelectionFilter,
            IsAvailableForAnyOrder isAvailableForAnyOrder,
            IsFreelyDispatchableToAnyVehicle isFreelyDispatchableToAnyVehicle,
            CompositeTransportOrderSelectionFilter transportOrderSelectionFilter,
            CompositeAssignmentCandidateSelectionFilter assignmentCandidateSelectionFilter,
            TransportOrderUtil transportOrderUtil) {
        this.router = requireNonNull(router, "router");
        this.objectService = requireNonNull(objectService, "objectService");
        this.orderReservationPool = requireNonNull(orderReservationPool, "orderReservationPool");
        this.vehicleComparator = requireNonNull(vehicleComparator, "vehicleComparator");
        this.orderComparator = requireNonNull(orderComparator, "orderComparator");
        this.orderCandidateComparator = requireNonNull(orderCandidateComparator,
                "orderCandidateComparator");
        this.vehicleCandidateComparator = requireNonNull(vehicleCandidateComparator,
                "vehicleCandidateComparator");
        this.vehicleSelectionFilter = requireNonNull(vehicleSelectionFilter, "vehicleSelectionFilter");
        this.isAvailableForAnyOrder = requireNonNull(isAvailableForAnyOrder, "isAvailableForAnyOrder");
        this.isFreelyDispatchableToAnyVehicle = requireNonNull(isFreelyDispatchableToAnyVehicle,
                "isFreelyDispatchableToAnyVehicle");
        this.transportOrderSelectionFilter = requireNonNull(transportOrderSelectionFilter,
                "transportOrderSelectionFilter");
        this.assignmentCandidateSelectionFilter = requireNonNull(assignmentCandidateSelectionFilter,
                "assignmentCandidateSelectionFilter");
        this.transportOrderUtil = requireNonNull(transportOrderUtil, "transportOrderUtil");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }
        initialized = true;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void terminate() {
        if (!isInitialized()) {
            return;
        }
        initialized = false;
    }

    @Override
    public void run() {
        Map<Boolean, List<VehicleFilterResult>> vehiclesSplitByFilter
                = objectService.fetchObjects(Vehicle.class, isAvailableForAnyOrder)
                .stream()//将小车-(过滤器)->过滤结果[VehicleFilterResult(小车，过滤排除原因)]
                .map(vehicle -> new VehicleFilterResult(vehicle, vehicleSelectionFilter.apply(vehicle)))
                .collect(Collectors.partitioningBy(filterResult -> !filterResult.isFiltered()));
        LOG.debug("【AssignFreeOrdersPhase.run.IsAvailableForAnyOrder.CompositeVehicleSelectionFilter(组合过滤器默认为空)】做完小车过滤，过滤结果:Map<Boolean, List<VehicleFilterResult>> = {}", vehiclesSplitByFilter);
        Collection<Vehicle> availableVehicles = vehiclesSplitByFilter.get(Boolean.TRUE).stream()
                .map(VehicleFilterResult::getVehicle)
                .collect(Collectors.toList());
        LOG.debug("【AssignFreeOrdersPhase.run.vehiclesSplitByFilter.collect】收集过滤结果，获取到availableVehicles:{}", availableVehicles);

        if (availableVehicles.isEmpty()) {
            LOG.debug("No vehicles available, skipping potentially expensive fetching of orders.");
            return;
        }
        LOG.debug("【AssignFreeOrdersPhase.run.IsAvailableForAnyOrder.CompositeVehicleSelectionFilter(组合过滤器默认为空)】做完小车过滤，获取到availableVehicles:{}", availableVehicles);

        // Select only dispatchable orders first, then apply the composite filter, handle
        // the orders that can be tried as usual and mark the others as filtered (if they aren't, yet).
        Map<Boolean, List<OrderFilterResult>> ordersSplitByFilter
                = objectService.fetchObjects(TransportOrder.class, isFreelyDispatchableToAnyVehicle)
                .stream()
                .map(order -> new OrderFilterResult(order, transportOrderSelectionFilter.apply(order)))
                .collect(Collectors.partitioningBy(filterResult -> !filterResult.isFiltered()));
        LOG.debug("【AssignFreeOrdersPhase.run.IsFreelyDispatchableToAnyVehicle.CompositeTransportOrderSelectionFilter(组合过滤器默认为空)】做完订单过滤，过滤结果:Map<Boolean, List<OrderFilterResult>> = {}", ordersSplitByFilter);
        //将被过滤掉的订单的不可用原因记录到订单的历史信息中
        markNewlyFilteredOrders(ordersSplitByFilter.get(Boolean.FALSE));
        LOG.debug("【AssignFreeOrdersPhase.run】做完订单过滤，获取到availableOrders:{}", ordersSplitByFilter.get(Boolean.TRUE));
        LOG.debug("【AssignFreeOrdersPhase.run】开始执行分配tryAssignments");

        tryAssignments(availableVehicles,
                ordersSplitByFilter.get(Boolean.TRUE).stream()
                        .map(OrderFilterResult::getOrder)
                        .collect(Collectors.toList()));
    }

    /**
     * 1、比较availableVehicles与availableOrders数量
     * 1.1、车<订单，则对小车先排序CompositeVehicleComparator，然后给小车分配订单tryAssignOrder
     * 1.2、车>订单，则对订单优先级排序CompositeOrderComparator，然后给订单分配小车tryAssignVehicle
     * <p>
     * 2、
     *
     * @author Rico
     * @date 2019/12/6 9:49 上午
     */
    private void tryAssignments(Collection<Vehicle> availableVehicles,
                                Collection<TransportOrder> availableOrders) {
        LOG.debug("Available for dispatching: {} transport orders and {} vehicles.",
                availableOrders.size(),
                availableVehicles.size());

        AssignmentState assignmentState = new AssignmentState();
        if (availableVehicles.size() < availableOrders.size()) {
            LOG.debug("【AssignFreeOrderPhase.run.tryAssignments】[可用的小车数量] < [可分配自由订单数量] 的情况下:所有[可用小车]->小车排序->tryAssignOrder");

            availableVehicles.stream()
                    .sorted(vehicleComparator)
                    .forEach(vehicle -> tryAssignOrder(vehicle, availableOrders, assignmentState));
        } else {
            LOG.debug("【AssignFreeOrderPhase.run.tryAssignments】[可用的小车数量]>[可分配自由订单数量]的情况下:所有[可分配自由订单]->小车排序->tryAssignVehicle");
            availableOrders.stream()
                    //org.opentcs.strategies.basic.dispatching.priorization.CompositeOrderComparator
                    .sorted(orderComparator)
                    .forEach(order -> tryAssignVehicle(order, availableVehicles, assignmentState));
        }
        //小车、订单、驾驶单的 候选项、终选项分配完成后，执行标记动作。
        assignmentState.getFilteredOrders().values().stream()
                .filter(filterResult -> !assignmentState.wasAssignedToVehicle(filterResult.getOrder()))
                .filter(this::filterReasonsChanged)
                .forEach(this::doMarkAsFiltered);

        availableOrders.stream()
                .filter(order -> (!assignmentState.wasFiltered(order)
                        && !assignmentState.wasAssignedToVehicle(order)))
                .filter(this::markedAsFiltered)
                .forEach(this::doUnmarkAsFiltered);
    }

    private void markNewlyFilteredOrders(Collection<OrderFilterResult> filterResults) {
        filterResults.stream()
                .filter(filterResult -> (!markedAsFiltered(filterResult.getOrder())
                        || filterReasonsChanged(filterResult)))
                .forEach(filterResult -> doMarkAsFiltered(filterResult));
    }

    private boolean markedAsFiltered(TransportOrder order) {
        return lastRelevantDeferredHistoryEntry(order).isPresent();
    }

    private Optional<ObjectHistory.Entry> lastRelevantDeferredHistoryEntry(TransportOrder order) {
        return order.getHistory().getEntries().stream()
                .filter(entry -> equalsAny(entry.getEventCode(),
                        ORDER_DISPATCHING_DEFERRED,
                        ORDER_DISPATCHING_RESUMED))
                .reduce((firstEntry, secondEntry) -> secondEntry)
                .filter(entry -> entry.getEventCode().equals(ORDER_DISPATCHING_DEFERRED));
    }

    @SuppressWarnings("unchecked")
    private boolean filterReasonsChanged(OrderFilterResult filterResult) {
        Collection<String> newReasons = filterResult.getFilterReasons();
        Collection<String> oldReasons = lastRelevantDeferredHistoryEntry(filterResult.getOrder())
                .map(entry -> (Collection<String>) entry.getSupplement())
                .orElse(new ArrayList<>());

        return newReasons.size() != oldReasons.size()
                || !newReasons.containsAll(oldReasons);
    }

    private void doMarkAsFiltered(OrderFilterResult filterResult) {
        objectService.appendObjectHistoryEntry(
                filterResult.getOrder().getReference(),
                new ObjectHistory.Entry(
                        ORDER_DISPATCHING_DEFERRED,
                        Collections.unmodifiableList(new ArrayList<>(filterResult.getFilterReasons()))
                )
        );
    }

    private void doUnmarkAsFiltered(TransportOrder order) {
        objectService.appendObjectHistoryEntry(
                order.getReference(),
                new ObjectHistory.Entry(
                        ORDER_DISPATCHING_RESUMED,
                        Collections.unmodifiableList(new ArrayList<>())
                )
        );
    }

    private boolean equalsAny(String string, String... others) {
        return Arrays.asList(others).stream()
                .anyMatch(other -> string.equals(other));
    }

    /**
     * 给小车分配订单
     *
     * @author Rico
     * @date 2019/12/6 10:56 上午
     */
    private void tryAssignOrder(Vehicle vehicle,
                                Collection<TransportOrder> availableOrders,
                                AssignmentState assignmentState) {
        LOG.debug("Trying to find transport order for vehicle '{}'...", vehicle.getName());
        LOG.debug("【AssignFreeOrderPhase.run.tryAssignments.tryAssignOrder】开始给小车'{}'分配订单...", vehicle.getName());

        Point vehiclePosition = objectService.fetchObject(Point.class, vehicle.getCurrentPosition());

        Map<Boolean, List<CandidateFilterResult>> ordersSplitByFilter
                = availableOrders.stream()
                .filter(order -> (!assignmentState.wasAssignedToVehicle(order)
                        && orderAssignableToVehicle(order, vehicle)))
                .map(order -> computeCandidate(vehicle, vehiclePosition, order))
                .filter(optCandidate -> optCandidate.isPresent())
                .map(optCandidate -> optCandidate.get())
                .map(candidate -> new CandidateFilterResult(candidate, assignmentCandidateSelectionFilter.apply(candidate)))
                .collect(Collectors.partitioningBy(filterResult -> !filterResult.isFiltered()));

        ordersSplitByFilter.get(Boolean.FALSE).stream()
                .map(CandidateFilterResult::toFilterResult)
                .forEach(filterResult -> assignmentState.addFilteredOrder(filterResult));

        ordersSplitByFilter.get(Boolean.TRUE).stream()
                .map(CandidateFilterResult::getCandidate)
                .sorted(orderCandidateComparator)
                .findFirst()
                .ifPresent(candidate -> assignOrder(candidate, assignmentState));
    }

    private void tryAssignVehicle(TransportOrder order,
                                  Collection<Vehicle> availableVehicles,
                                  AssignmentState assignmentState) {
        LOG.debug("Trying to find vehicle for transport order '{}'...", order.getName());
        LOG.debug("【AssignFreeOrderPhase.run.tryAssignments.tryAssignVehicle】开始遍历小车，给订单'{}'分配小车(未分配订单，能承运本(订单预用小车或预用本小车)订单)...", order.getName());
        //获取到所有候选项(小车、运单、驾驶单s、成本)
        Stream<AssignmentCandidate> assignmentCandidateStream = availableVehicles.stream()
                .filter(vehicle -> (!assignmentState.wasAssignedToOrder(vehicle)
                        && orderAssignableToVehicle(order, vehicle)))
                .map(vehicle -> computeCandidate(vehicle,
                        objectService.fetchObject(Point.class,
                                vehicle.getCurrentPosition()),
                        order))
                .filter(optCandidate -> optCandidate.isPresent())
                .map(optCandidate -> optCandidate.get());
        //将所有候选项交给【候选过滤器assignmentCandidateSelectionFilter】,获取最终选用项
        Map<Boolean, List<CandidateFilterResult>> ordersSplitByFilter = assignmentCandidateStream
                .map(candidate -> new CandidateFilterResult(candidate, assignmentCandidateSelectionFilter.apply(candidate)))
                .collect(Collectors.partitioningBy(filterResult -> !filterResult.isFiltered()));
        LOG.debug("【AssignFreeOrdersPhase.*.tryAssignVehicle.CompositeAssignmentCandidateSelectionFilter(组合过滤器默认为IsProcessable,用于检查小车的Operation是否包含所有驾驶单中的Operation)】做完候选项过滤，过滤结果:Map<Boolean, List<CandidateFilterResult>> = {}", ordersSplitByFilter);

        ordersSplitByFilter.get(Boolean.FALSE).stream()
                .map(CandidateFilterResult::toFilterResult)
                .forEach(filterResult -> assignmentState.addFilteredOrder(filterResult));

        LOG.debug("【AssignFreeOrdersPhase.*.tryAssignVehicle.CompositeVehicleCandidateComparator(候选小车比较器).findFirst.assignOrder】");
        ordersSplitByFilter.get(Boolean.TRUE).stream()
                .map(CandidateFilterResult::getCandidate)
                .sorted(vehicleCandidateComparator)
                .findFirst()
                .ifPresent(candidate -> assignOrder(candidate, assignmentState));
    }

    private void assignOrder(AssignmentCandidate candidate, AssignmentState assignmentState) {
        // If the vehicle currently has a (dispensable) order, we may not assign the new one here
        // directly, but must abort the old one (DefaultDispatcher.abortOrder()) and wait for the
        // vehicle's ProcState to become IDLE.
        LOG.debug("【AssignFreeOrdersPhase.*.tryAssignVehicle.CompositeVehicleCandidateComparator(候选小车比较器).findFirst.assignOrder】开始执行终选项分配:::If the vehicle currently has a (dispensable) order, we may not assign the new one here directly, but must abort the old one (DefaultDispatcher.abortOrder()) and wait for the vehicle's ProcState to become IDLE.");
        LOG.debug(" If the vehicle currently has a (dispensable) order, we may not assign the new one here directly, but must abort the old one (DefaultDispatcher.abortOrder()) and wait for the vehicle's ProcState to become IDLE.");
        if (candidate.getVehicle().getTransportOrder() == null) {
            LOG.debug("Assigning transport order '{}' to vehicle '{}'...",
                    candidate.getTransportOrder().getName(),
                    candidate.getVehicle().getName());
            doMarkAsAssigned(candidate.getTransportOrder(), candidate.getVehicle());//标记订单已经被分配给小车(在订单历史中添加记录)
            LOG.debug("==================完成最优方案选定， 开始进行运单与小车绑定及运单拆解==================");
            transportOrderUtil.assignTransportOrder(candidate.getVehicle(), candidate.getTransportOrder(), candidate.getDriveOrders());//执行运单分配
            LOG.debug("==================完成最优方案选定， 完成进行运单与小车绑定及运单拆解==================");
            assignmentState.getAssignedCandidates().add(candidate);
        } else {
            LOG.debug("Reserving transport order '{}' for vehicle '{}'...",
                    candidate.getTransportOrder().getName(),
                    candidate.getVehicle().getName());
            // Remember that the new order is reserved for this vehicle.
            doMarkAsReserved(candidate.getTransportOrder(), candidate.getVehicle());
            orderReservationPool.addReservation(candidate.getTransportOrder().getReference(),
                    candidate.getVehicle().getReference());
            assignmentState.getReservedCandidates().add(candidate);
            transportOrderUtil.abortOrder(candidate.getVehicle(), false, false, false);
        }
    }

    private void doMarkAsAssigned(TransportOrder order, Vehicle vehicle) {
        objectService.appendObjectHistoryEntry(
                order.getReference(),
                new ObjectHistory.Entry(ORDER_ASSIGNED_TO_VEHICLE, vehicle.getName())
        );
    }

    private void doMarkAsReserved(TransportOrder order, Vehicle vehicle) {
        objectService.appendObjectHistoryEntry(
                order.getReference(),
                new ObjectHistory.Entry(ORDER_RESERVED_FOR_VEHICLE, vehicle.getName())
        );
    }

    private Optional<AssignmentCandidate> computeCandidate(Vehicle vehicle,
                                                           Point vehiclePosition,
                                                           TransportOrder order) {
        LOG.debug("开始试算{}点位上的{}小车执行{}订单所需要的运输成本", vehiclePosition.getName(), vehicle.getName(), order.getName());
        Optional<List<DriveOrder>> optionalDriveOrderList = router.getRoute(vehicle, vehiclePosition, order);
        LOG.debug("新增候选项，该候选项getRoute获取最短路径调用返回结果Optional<List<DriveOrder>>为:::【{}】", optionalDriveOrderList);
        return optionalDriveOrderList
                .map(driveOrders -> new AssignmentCandidate(vehicle, order, driveOrders));
    }

    private boolean orderAssignableToVehicle(TransportOrder order, Vehicle vehicle) {
        return order.getIntendedVehicle() == null
                || Objects.equals(order.getIntendedVehicle(), vehicle.getReference());
    }
}
