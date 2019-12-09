驱动下发next step指令

StandardRemoteVehicleService#sendCommAdapterCommand(TriggerCommand)->``singleStepExecutionAllowed=true`

BasicVehicleCommAdapter.CommandDispatcherTask轮训任务调用:

LoopbackCommunicationAdapter#sendCommand

LoopbackCommunicationAdapter模拟完成了指令下发操作。

```
singleStepExecutionAllowed = false;
```

BasicVehicleCommAdapter.CommandDispatcherTask将指令添加到sentQueue中

告知VehicleProcessModel小车模型，移动指令已被发送给小车





## 小车运行过程：

1. 驱动调用 updateVehiclePosition更新小车当前位置
2. model.setVehiclePosition(vehicleRef, pointRef);更新内核中的小车模型数据



