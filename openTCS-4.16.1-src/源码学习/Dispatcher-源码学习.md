# DefaultDispatcher源码梳理

org.opentcs.strategies.basic.dispatching.DefaultDispatcher

## 1.初始化(注册非显式触发全局调度任务)

```java
  @Override
  public void initialize() {
    if (isInitialized()) {
      return;
    }

    LOG.debug("Initializing Default Dispatcher...");

    transportOrderUtil.initialize();
    orderReservationPool.clear();

    fullDispatchTask.initialize();

    implicitDispatchTrigger = new ImplicitDispatchTrigger(this);
    // 添加 监听式隐式触发器 用于消费应用事件
    eventSource.subscribe(implicitDispatchTrigger);

    LOG.debug("Scheduling periodic dispatch task with interval of {} ms...",
              configuration.idleVehicleRedispatchingInterval());
    //初始化周期性运单分配任务
    periodicDispatchTaskFuture = kernelExecutor.scheduleAtFixedRate(
        periodicDispatchTaskProvider.get(),
        configuration.idleVehicleRedispatchingInterval(),
        configuration.idleVehicleRedispatchingInterval(),
        TimeUnit.MILLISECONDS
    );

    initialized = true;
  }
```

### 1.1.ImplicitDispatchTrigger

注册监听小车状态变更:

```java
    implicitDispatchTrigger = new ImplicitDispatchTrigger(this);
    // 添加 监听式隐式触发器 用于消费应用事件
    eventSource.subscribe(implicitDispatchTrigger);
```

implicitDispatchTrigger.onEvent.checkVehicleChange：

监听到并处理关于小车事件并且小车状态满足:

```java

  private void checkVehicleChange(Vehicle oldVehicle, Vehicle newVehicle) {
    if ((newVehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED
         || newVehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_RESPECTED)
        && (idleAndEnergyLevelChanged(oldVehicle, newVehicle)
            || awaitingNextOrder(oldVehicle, newVehicle)
            || orderSequenceNulled(oldVehicle, newVehicle))) {
        LOG.debug("【ImplicitDispatchTrigger】::: 监听到并处理关于小车事件并且小车新状态为:TO_BE_UTILIZED || TO_BE_RESPECTED");

        LOG.debug("【ImplicitDispatchTrigger】::: 触发dispatcher.dispatch()方法，执行全局调度操作");
        LOG.debug("Dispatching for {}...", newVehicle);
      dispatcher.dispatch();
    }
  }


  private boolean idleAndEnergyLevelChanged(Vehicle oldVehicle, Vehicle newVehicle) {
    // If the vehicle is idle and its energy level changed, we may want to order it to recharge.
    return newVehicle.hasProcState(Vehicle.ProcState.IDLE)
        && (newVehicle.hasState(Vehicle.State.IDLE) || newVehicle.hasState(Vehicle.State.CHARGING))
        && newVehicle.getEnergyLevel() != oldVehicle.getEnergyLevel();
  }

  private boolean awaitingNextOrder(Vehicle oldVehicle, Vehicle newVehicle) {
    // If the vehicle's processing state changed to IDLE or AWAITING_ORDER, it is waiting for
    // its next order, so look for one.
    return newVehicle.getProcState() != oldVehicle.getProcState()
        && (newVehicle.hasProcState(Vehicle.ProcState.IDLE)
            || newVehicle.hasProcState(Vehicle.ProcState.AWAITING_ORDER));
  }

  private boolean orderSequenceNulled(Vehicle oldVehicle, Vehicle newVehicle) {
    // If the vehicle's order sequence reference has become null, the vehicle has just been released
    // from an order sequence, so we may look for new assignments.
    return newVehicle.getOrderSequence() == null
        && oldVehicle.getOrderSequence() != null;
  }

```



### 1.2.PeriodicVehicleRedispatchingTask

后台启动周期调度任务触发：

```java
    LOG.debug("Scheduling periodic dispatch task with interval of {} ms...",
              configuration.idleVehicleRedispatchingInterval());
    //初始化周期性运单分配任务
    periodicDispatchTaskFuture = kernelExecutor.scheduleAtFixedRate(
        periodicDispatchTaskProvider.get(),
        configuration.idleVehicleRedispatchingInterval(),
        configuration.idleVehicleRedispatchingInterval(),
        TimeUnit.MILLISECONDS
    );
```

周期任务触发条件:PeriodicVehicleRedispatchingTask.run.couldProcessTransportOrder:

判断小车{}是否可用于处理运单条件：

```java

  @Override
  public void run() {
      LOG.debug("每10s定时查看是否有可用空闲小车，如果有的话，将触发dispatcherService.dispatch();");
    // If there are any vehicles that could process a transport order,
    // trigger the dispatcher once.
    objectService.fetchObjects(Vehicle.class, this::couldProcessTransportOrder).stream()
        .findAny()
        .ifPresent(vehicle -> {
          LOG.debug("Vehicle {} could process transport order, triggering dispatcher ...", vehicle);
          dispatcherService.dispatch();
        });
  }

  private boolean couldProcessTransportOrder(Vehicle vehicle) {
      LOG.debug("判断小车{}是否可用于处理运单couldProcessTransportOrder: 条件：TO_BE_UTILIZED&&当前位置不为空&&电量级别严重&&(小车不在处理运单||正在处理可有可无的订单())",vehicle.getName());
    return vehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED
        && vehicle.getCurrentPosition() != null
        && !vehicle.isEnergyLevelCritical()
        && (processesNoOrder(vehicle)
            || processesDispensableOrder(vehicle));
  }

  private boolean processesNoOrder(Vehicle vehicle) {
    return vehicle.hasProcState(Vehicle.ProcState.IDLE)
        && (vehicle.hasState(Vehicle.State.IDLE)
            || vehicle.hasState(Vehicle.State.CHARGING));
  }

  private boolean processesDispensableOrder(Vehicle vehicle) {
    return vehicle.hasProcState(Vehicle.ProcState.PROCESSING_ORDER)
        && objectService.fetchObject(TransportOrder.class, vehicle.getTransportOrder())
            .isDispensable();
  }
```





## 2.全局调度任务FullDispatchTask

```java

    @Override
    public final void run() {

        LOG.debug("==============开始 FullDispatchTask.run ==============");
        LOG.debug("触发调度器的FullDispatchTask，执行任务分配：：： ");
        LOG.debug("Starting full dispatch run...");

        //Checks for transport orders that are still in state RAW, and attempts to prepare them for assignment.
        LOG.debug("1、检查RAW状态的订单，调整为: UNROUTABLE(router判定无法路由), ACTIVE（可路由）, DISPATCHABLE(没有其他未完成的依赖任务)");
        checkNewOrdersPhase.run();
        // Check what vehicles involved in a process should do.
        // Finishes withdrawals of transport orders after the vehicle has come to a halt.
        LOG.debug("2、处理AWAITING_ORDER的小车。如果小车身上存在WITHDRAWN的运单，则将小车(是否需要disable,需要则UNAVAILABLE否则IDLE)及运单状态(fail)做相应调整");
        finishWithdrawalsPhase.run();

        // * Assigns the next drive order to each vehicle waiting for it, or finishes the respective transport order if the vehicle has finished its last drive order.

        LOG.debug("3、分配下一个DriveOrder给AWAITING_ORDER的小车。checkForNextDriveOrder根据小车身上的TransportOrder.getCurrentDriveOrder()判断小车当前运单完成情况及是否需要分配下一个DriveOrder");
        assignNextDriveOrdersPhase.run();

        LOG.debug("4、根据order sequences分配下一个运单给小车。Assigns vehicles to the next transport orders in their respective order sequences, if any.");
        assignSequenceSuccessorsPhase.run();
        // Check what vehicles not already in a process should do.
        LOG.debug("5、分配订单给小车，首先分配已预订的订单，然后分配自由订单。Assignment of orders to vehicles.Default: Assigns reserved and then free orders to vehicles.");
        assignOrders();


        LOG.debug("6、Recharging of vehicles.Default: Sends idle vehicles with a degraded energy level to recharge locations.");
        rechargeVehicles();


        LOG.debug("7、Parking of vehicles.Default: Sends idle vehicles to parking positions.");
        parkVehicles();

        LOG.debug("Finished full dispatch run.");
        LOG.debug("==============结束 FullDispatchTask.run ==============");

    }

    /**
     * Assignment of orders to vehicles.
     * <p>
     * Default: Assigns reserved and then free orders to vehicles.
     * </p>
     */
    protected void assignOrders() {
        LOG.debug("5.1、分配已被预定的订单给小车。Assigns reserved transport orders (if any) to vehicles that have just finished their withdrawn ones.");
        assignReservedOrdersPhase.run();
        LOG.debug("5.2、分配订单给当前没有renew任务的小车。Assigns transport orders to vehicles that are currently not processing any and are not bound to any order sequences.");
        assignFreeOrdersPhase.run();
    }

    /**
     * Recharging of vehicles.
     * <p>
     * Default: Sends idle vehicles with a degraded energy level to recharge locations.
     * </p>
     */
    protected void rechargeVehicles() {

        LOG.debug("6.1、新建充电订单给Creates recharging orders for any vehicles with a degraded energy level.");
        rechargeIdleVehiclesPhase.run();
    }

    /**
     * Parking of vehicles.
     * <p>
     * Default: Sends idle vehicles to parking positions.
     * </p>
     */
    protected void parkVehicles() {
        LOG.debug("7.1、Creates parking orders for idle vehicles already at a parking position to send them to higher prioritized parking positions.");
        prioritizedReparkPhase.run();
        LOG.debug("7.2、Creates parking orders for idle vehicles not already at a parking position considering only prioritized parking positions.");
        prioritizedParkingPhase.run();
        LOG.debug("7.3、Creates parking orders for idle vehicles not already at a parking position considering all parking positions.");
        parkIdleVehiclesPhase.run();
    }
```



### 2.1.checkNewOrdersPhase

```java

  @Override
  public void run() {
    objectService.fetchObjects(TransportOrder.class, this::inRawState).stream()
        .forEach(order -> checkRawTransportOrder(order));
  }

  private void checkRawTransportOrder(TransportOrder order) {
    requireNonNull(order, "order");

    // Check if the transport order is routable.
    if (configuration.dismissUnroutableTransportOrders()
        && router.checkRoutability(order).isEmpty()) {
      transportOrderUtil.updateTransportOrderState(order.getReference(),
                                                   TransportOrder.State.UNROUTABLE);
      return;
    }
    transportOrderUtil.updateTransportOrderState(order.getReference(),
                                                 TransportOrder.State.ACTIVE);
    // The transport order has been activated - dispatch it.
    // Check if it has unfinished dependencies.
    if (!transportOrderUtil.hasUnfinishedDependencies(order)) {
      transportOrderUtil.updateTransportOrderState(order.getReference(),
                                                   TransportOrder.State.DISPATCHABLE);
    }
  }

  private boolean inRawState(TransportOrder order) {
    return order.hasState(TransportOrder.State.RAW);
  }
```



### 2.2.finishWithdrawalsPhase

```java

  @Override
  public void run() {
    objectService.fetchObjects(Vehicle.class).stream()
        .filter(vehicle -> vehicle.hasProcState(Vehicle.ProcState.AWAITING_ORDER))
        .filter(vehicle -> hasWithdrawnTransportOrder(vehicle))
        .forEach(vehicle -> transportOrderUtil.finishAbortion(vehicle));
  }

  private boolean hasWithdrawnTransportOrder(Vehicle vehicle) {
    return objectService.fetchObject(TransportOrder.class, vehicle.getTransportOrder())
        .hasState(TransportOrder.State.WITHDRAWN);
  }

```



### 2.3.assignNextDriveOrdersPhase
```java

  @Override
  public void run() {
    transportOrderService.fetchObjects(Vehicle.class).stream()
        .filter(vehicle -> vehicle.hasProcState(Vehicle.ProcState.AWAITING_ORDER))
        .forEach(vehicle -> checkForNextDriveOrder(vehicle));
  }

  private void checkForNextDriveOrder(Vehicle vehicle) {
    LOG.debug("Vehicle '{}' finished a drive order.", vehicle.getName());
    // The vehicle is processing a transport order and has finished a drive order.
    // See if there's another drive order to be processed.
    transportOrderService.updateTransportOrderNextDriveOrder(vehicle.getTransportOrder());
    TransportOrder vehicleOrder = transportOrderService.fetchObject(TransportOrder.class,
                                                                    vehicle.getTransportOrder());
    if (vehicleOrder.getCurrentDriveOrder() == null) {
      LOG.debug("Vehicle '{}' finished transport order '{}'",
                vehicle.getName(),
                vehicleOrder.getName());
      // The current transport order has been finished - update its state and that of the vehicle.
      transportOrderUtil.updateTransportOrderState(vehicle.getTransportOrder(),
                                                   TransportOrder.State.FINISHED);
      // Update the vehicle's procState, implicitly dispatching it again.
      vehicleService.updateVehicleProcState(vehicle.getReference(), Vehicle.ProcState.IDLE);
      vehicleService.updateVehicleTransportOrder(vehicle.getReference(), null);
      // Let the router know that the vehicle doesn't have a route any more.
      router.selectRoute(vehicle, null);
      // Update transport orders that are dispatchable now that this one has been finished.
      transportOrderUtil.markNewDispatchableOrders();
    }
    else {
      LOG.debug("Assigning next drive order to vehicle '{}'...", vehicle.getName());
      // Get the next drive order to be processed.
      DriveOrder currentDriveOrder = vehicleOrder.getCurrentDriveOrder();
      if (transportOrderUtil.mustAssign(currentDriveOrder, vehicle)) {
        if (configuration.rerouteTrigger() == DRIVE_ORDER_FINISHED) {
          LOG.debug("Trying to reroute vehicle '{}' before assigning the next drive order...",
                    vehicle.getName());
          rerouteUtil.reroute(vehicle);
        }
        
        // Get an up-to-date copy of the transport order in case the route changed
        vehicleOrder = transportOrderService.fetchObject(TransportOrder.class,
                                                         vehicle.getTransportOrder());
        currentDriveOrder = vehicleOrder.getCurrentDriveOrder();

        // Let the vehicle controller know about the new drive order.
        vehicleControllerPool.getVehicleController(vehicle.getName())
            .setDriveOrder(currentDriveOrder, vehicleOrder.getProperties());

        // The vehicle is still processing a transport order.
        vehicleService.updateVehicleProcState(vehicle.getReference(),
                                              Vehicle.ProcState.PROCESSING_ORDER);
      }
      // If the drive order need not be assigned, immediately check for another one.
      else {
        vehicleService.updateVehicleProcState(vehicle.getReference(),
                                              Vehicle.ProcState.AWAITING_ORDER);
        checkForNextDriveOrder(vehicle);
      }
    }
  }

```
### 2.4.assignSequenceSuccessorsPhase
```java

  @Override
  public void run() {
    for (Vehicle vehicle : objectService.fetchObjects(Vehicle.class,
                                                      this::readyForNextInSequence)) {
      tryAssignNextOrderInSequence(vehicle);
    }
  }

  private void tryAssignNextOrderInSequence(Vehicle vehicle) {
    nextOrderInCurrentSequence(vehicle)
        .map(order -> computeCandidate(vehicle, order))
        .filter(candidate -> assignmentCandidateSelectionFilter.apply(candidate).isEmpty())
        .ifPresent(candidate -> transportOrderUtil.assignTransportOrder(vehicle,
                                                                        candidate.getTransportOrder(),
                                                                        candidate.getDriveOrders()));
  }

  private AssignmentCandidate computeCandidate(Vehicle vehicle, TransportOrder order) {
    return router.getRoute(vehicle,
                           objectService.fetchObject(Point.class, vehicle.getCurrentPosition()),
                           order)
        .map(driveOrders -> new AssignmentCandidate(vehicle, order, driveOrders))
        .orElse(null);
  }

  private Optional<TransportOrder> nextOrderInCurrentSequence(Vehicle vehicle) {
    OrderSequence seq = objectService.fetchObject(OrderSequence.class, vehicle.getOrderSequence());

    // If the order sequence's next order is not available, yet, the vehicle should wait for it.
    if (seq.getNextUnfinishedOrder() == null) {
      return Optional.empty();
    }

    // Return the next order to be processed for the sequence.
    return Optional.of(objectService.fetchObject(TransportOrder.class,
                                                 seq.getNextUnfinishedOrder()));
  }

  private boolean readyForNextInSequence(Vehicle vehicle) {
    return vehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED
        && vehicle.hasProcState(Vehicle.ProcState.IDLE)
        && vehicle.hasState(Vehicle.State.IDLE)
        && vehicle.getCurrentPosition() != null
        && vehicle.getOrderSequence() != null;
  }

```
### 2.5.assignOrders

```java

```
#### 2.5.1.assignReservedOrdersPhase

```java

  @Override
  public void run() {
    for (Vehicle vehicle : objectService.fetchObjects(Vehicle.class, this::available)) {
      checkForReservedOrder(vehicle);
    }
  }

  private void checkForReservedOrder(Vehicle vehicle) {
    // Check if there's an order reserved for this vehicle that is in an assignable state. If yes,
    // try to assign that.
    // Note that we expect no more than a single reserved order, and remove ALL reservations if we
    // find at least one, even if it cannot be processed by the vehicle in the end.
    orderReservationPool.findReservations(vehicle.getReference()).stream()
        .map(orderRef -> objectService.fetchObject(TransportOrder.class, orderRef))
        .filter(order -> order.hasState(TransportOrder.State.DISPATCHABLE))
        .limit(1)
        .peek(order -> orderReservationPool.removeReservations(vehicle.getReference()))
        .map(order -> computeCandidate(vehicle,
                                       objectService.fetchObject(Point.class,
                                                                 vehicle.getCurrentPosition()),
                                       order))
        .filter(optCandidate -> optCandidate.isPresent())
        .map(optCandidate -> optCandidate.get())
        .filter(candidate -> assignmentCandidateSelectionFilter.apply(candidate).isEmpty())
        .findFirst()
        .ifPresent(
            candidate -> transportOrderUtil.assignTransportOrder(vehicle,
                                                                 candidate.getTransportOrder(),
                                                                 candidate.getDriveOrders())
        );
  }

  private boolean available(Vehicle vehicle) {
    return vehicle.hasProcState(Vehicle.ProcState.IDLE)
        && (vehicle.hasState(Vehicle.State.IDLE)
            || vehicle.hasState(Vehicle.State.CHARGING));
  }

  private Optional<AssignmentCandidate> computeCandidate(Vehicle vehicle,
                                                         Point vehiclePosition,
                                                         TransportOrder order) {
    return router.getRoute(vehicle, vehiclePosition, order)
        .map(driveOrders -> new AssignmentCandidate(vehicle, order, driveOrders));
  }

```
#### 2.5.2.assignFreeOrdersPhase

```java

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
        //小车、订单、驾驶单的 候选项、终选项分配完成后
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
            transportOrderUtil.assignTransportOrder(candidate.getVehicle(),
                    candidate.getTransportOrder(),
                    candidate.getDriveOrders());//执行运单分配
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
        LOG.debug("【AssignFreeOrderPhase.run.tryAssignments.computeCandidate】开始给{}点位上的{}小车分配{}订单", vehiclePosition.getName(), vehicle.getName(), order.getName());
        Optional<List<DriveOrder>> optionalDriveOrderList = router.getRoute(vehicle, vehiclePosition, order);
        LOG.debug("【AssignFreeOrderPhase.run.tryAssignments.computeCandidate.router.getRoute】getRoute获取最短路径调用返回结果Optional<List<DriveOrder>>为:::【{}】", optionalDriveOrderList);
        return optionalDriveOrderList
                .map(driveOrders -> new AssignmentCandidate(vehicle, order, driveOrders));
    }

    private boolean orderAssignableToVehicle(TransportOrder order, Vehicle vehicle) {
        return order.getIntendedVehicle() == null
                || Objects.equals(order.getIntendedVehicle(), vehicle.getReference());
    }
```
### 2.6.rechargeVehicles

#### 2.6.1.rechargeIdleVehiclesPhase

```java

  @Override
  public void run() {
    if (!configuration.rechargeIdleVehicles()) {
      return;
    }

    orderService.fetchObjects(Vehicle.class).stream()
        .filter(vehicle -> vehicleSelectionFilter.apply(vehicle).isEmpty())
        .forEach(vehicle -> createRechargeOrder(vehicle));
  }

  private void createRechargeOrder(Vehicle vehicle) {
    List<DriveOrder.Destination> rechargeDests = rechargePosSupplier.findRechargeSequence(vehicle);
    LOG.debug("Recharge sequence for {}: {}", vehicle, rechargeDests);

    if (rechargeDests.isEmpty()) {
      LOG.info("{}: Did not find a suitable recharge sequence.", vehicle.getName());
      return;
    }

    List<DestinationCreationTO> chargeDests = new ArrayList<>(rechargeDests.size());
    for (DriveOrder.Destination dest : rechargeDests) {
      chargeDests.add(
          new DestinationCreationTO(dest.getDestination().getName(), dest.getOperation())
              .withProperties(dest.getProperties())
      );
    }
    // Create a transport order for recharging and verify its processability.
    // The recharge order may be withdrawn unless its energy level is critical.
    TransportOrder rechargeOrder = orderService.createTransportOrder(
        new TransportOrderCreationTO("Recharge-", chargeDests)
            .withIncompleteName(true)
            .withIntendedVehicleName(vehicle.getName())
            .withDispensable(!vehicle.isEnergyLevelCritical())
    );

    Point vehiclePosition = orderService.fetchObject(Point.class, vehicle.getCurrentPosition());
    Optional<AssignmentCandidate> candidate = computeCandidate(vehicle,
                                                               vehiclePosition,
                                                               rechargeOrder)
        .filter(c -> assignmentCandidateSelectionFilter.apply(c).isEmpty());
    // XXX Change this to Optional.ifPresentOrElse() once we're at Java 9+.
    if (candidate.isPresent()) {
      transportOrderUtil.assignTransportOrder(candidate.get().getVehicle(),
                                              candidate.get().getTransportOrder(),
                                              candidate.get().getDriveOrders());
    }
    else {
      // Mark the order as failed, since the vehicle cannot execute it.
      orderService.updateTransportOrderState(rechargeOrder.getReference(),
                                             TransportOrder.State.FAILED);
    }
  }

  private Optional<AssignmentCandidate> computeCandidate(Vehicle vehicle,
                                                         Point vehiclePosition,
                                                         TransportOrder order) {
    return router.getRoute(vehicle, vehiclePosition, order)
        .map(driveOrders -> new AssignmentCandidate(vehicle, order, driveOrders));
  }

```
### 2.7.parkVehicles

#### 2.7.1.prioritizedReparkPhase

```java

  @Override
  public void run() {
    if (!getConfiguration().parkIdleVehicles()
        || !getConfiguration().considerParkingPositionPriorities()
        || !getConfiguration().reparkVehiclesToHigherPriorityPositions()) {
      return;
    }

    LOG.debug("Looking for parking vehicles to send to higher prioritized parking positions...");

    getOrderService().fetchObjects(Vehicle.class).stream()
        .filter(vehicle -> vehicleSelectionFilter.apply(vehicle).isEmpty())
        .sorted((vehicle1, vehicle2) -> {
          // Sort the vehicles based on the priority of the parking position they occupy
          Point point1 = getOrderService().fetchObject(Point.class, vehicle1.getCurrentPosition());
          Point point2 = getOrderService().fetchObject(Point.class, vehicle2.getCurrentPosition());
          return priorityComparator.compare(point1, point2);
        })
        .forEach(vehicle -> createParkingOrder(vehicle));
  }
```

#### 2.7.2.prioritizedParkingPhase

```java

  @Override
  public void run() {
    if (!getConfiguration().parkIdleVehicles()
        || !getConfiguration().considerParkingPositionPriorities()) {
      return;
    }

    LOG.debug("Looking for vehicles to send to prioritized parking positions...");

    getOrderService().fetchObjects(Vehicle.class).stream()
        .filter(vehicle -> vehicleSelectionFilter.apply(vehicle).isEmpty())
        .forEach(vehicle -> createParkingOrder(vehicle));
  }

```



#### 2.7.3.parkIdleVehiclesPhase

```java

  @Override
  public void run() {
    if (!getConfiguration().parkIdleVehicles()) {
      return;
    }

    LOG.debug("Looking for vehicles to send to parking positions...");

    getOrderService().fetchObjects(Vehicle.class).stream()
        .filter(vehicle -> vehicleSelectionFilter.apply(vehicle).isEmpty())
        .forEach(vehicle -> createParkingOrder(vehicle));
  }
```

