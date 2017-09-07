## pigeon版本发布说明
------

### 2.9.7(recommended) 
Future模式失败降级配置异常问题修复

### 2.9.6

线程池参数调优

### 2.9.5

服务隔离优化

### 2.9.4

性能优化

### 2.9.3

console埋点优化改进
服务隔离优化改进

### 2.9.2

增加netty版本冲突的兼容性适配

### 2.9.1

增加PublishPolicy自定义接口

增加服务隔离的多种动态组合配置

log4j的框架context与业务隔离

埋点healthcheck的服务信息

修正ServiceOnlineTask问题，修正和改进ServiceInitializeListener相关类

修改netty初始化代码，确保netty内部是daemon线程

初始化线程池时就启动所有核心线程

提供服务治理相关统计信息HTTP接口

### 2.9.0

方法降级策略未指定时，进入正常调用流程(之前版本返回降级值null)

自动降级策略包含失败降级策略

升级pigeon的spring依赖为3.2.9版本，规避低版本spring-asm带来的冲突问题

客户端增加连接池，支持多连接，提升性能

重构长连接重连和客户端心跳

增加了4080/onlineStatus显示当前注册情况

fst的版本冲突时，并且client不使用fst时，可以忽略pigeon的fst报错

app name未填写，抛出异常
