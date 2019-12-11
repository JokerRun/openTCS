# openTCS 源码学习

本项目中，已在多个重要节点添加日志输出。原始代码请在历史提交记录中查看。





## 1. 环境准备

1. 将`openTCS/openTCS-4.16.1-src`导入IDEA

2. 配置`org.opentcs.kernel.RunKernel`启动方式

3. 详见[OpenTCS-IDEA-Debug](./openTCS-4.16.1-src/源码学习/OpenTCS-IDEA-Debug环境配置.md)

4. build源码

5. 调整log配置`openTCS-Kernel/build/install/openTCS-Kernel/config/logging.config`

   ```properties
   # 配置文件地址：openTCS-Kernel/build/install/openTCS-Kernel/config/logging.config
   # 1、指定全局所有class默认以INFO级别打印
   .level= INFO
   
   # 2、指定包、类的日志输出级别
   org.opentcs.util.CyclicTask.level = ALL
   org.opentcs.drivers.vehicle.BasicVehicleCommAdapter.level = ALL
   org.opentcs.kernel.KernelStateOperating.level = ALL
   org.opentcs.strategies.basic.level = ALL
   org.opentcs.kernel.services.level = ALL
   org.opentcs.virtualvehicle.level = ALL
   org.opentcs.kernel.vehicles.level = ALL
   org.opentcs.kernel.workingset.level = INFO
   org.opentcs.level = INFO
   java.level = WARNING
   org.eclipse = WARNING
   javax.level = WARNING
   sun.level = WARNING
   com.level = WARNING
   
   # 3、限制控制台最高输出级别(不管2中特定包的级别多高，都会在这里被过滤掉)
   handlers= java.util.logging.ConsoleHandler
   java.util.logging.ConsoleHandler.level = ALL
   java.util.logging.ConsoleHandler.formatter = org.opentcs.util.logging.SingleLineFormatter
   ```

![image-20191210145525582](assets/image-20191210145525582.png)

## 2.启动openTCS-Kernel

以DEBUG方式启动内核项目

![image-20191210145026384](assets/image-20191210145026384.png)



## 3.常规方式启动`KernelControllerCenter`及`PlantOverview`

```sh
# 
cd apps/openTCS-4.16.1-bin/openTCS-KernelControlCenter
./startKernelControlCenter.sh
```

```sh
cd apps/openTCS-4.16.1-bin/openTCS-PlantOverview 
./startPlantOverview.sh
```



## 4.开始debug。。。







## 问题处理

RMI远程连接Kernel时，会出现无法连接的问题，参见https://sourceforge.net/p/opentcs/mailman/message/36661250/

处理方式为：在内核启动时，添加JVM参数 `-Djava.rmi.server.hostname=machine001`：

```shell
vim /opt/openTCS/openTCS-Kernel/startKernel.sh
```

```shell
#!/bin/sh
#
# Start the openTCS kernel.
#

# Set base directory names.
export OPENTCS_BASE=.
export OPENTCS_HOME=.
export OPENTCS_CONFIGDIR="${OPENTCS_HOME}/config"
export OPENTCS_LIBDIR="${OPENTCS_BASE}/lib"

# Set the class path
export OPENTCS_CP="${OPENTCS_LIBDIR}/*"
export OPENTCS_CP="${OPENTCS_CP}:${OPENTCS_LIBDIR}/openTCS-extensions/*"

if [ -n "${OPENTCS_JAVAVM}" ]; then
    export JAVA="${OPENTCS_JAVAVM}"
else
    # XXX Be a bit more clever to find out the name of the JVM runtime.
    export JAVA="java"
fi

# Start kernel
${JAVA} -enableassertions \
    -Dopentcs.base="${OPENTCS_BASE}" \
    -Dopentcs.home="${OPENTCS_HOME}" \
    -Djava.util.logging.config.file=${OPENTCS_CONFIGDIR}/logging.config -Djava.rmi.server.hostname=machine001 \
    -Djava.security.policy=file:${OPENTCS_CONFIGDIR}/java.policy \
    -XX:-OmitStackTraceInFastThrow \
    -classpath "${OPENTCS_CP}" \
    org.opentcs.kernel.RunKernel
root@machine001:/opt/openTCS/openTCS-Kernel#

```

