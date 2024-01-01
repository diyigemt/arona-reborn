# 策略

权限系统核心配置

## 设计基础

整套权限系统采用 RBAC（Role-Based Access Control，基于角色的访问控制）和 ABAC（Attribute-Based Access Control，基于属性的访问控制）相结合的设计思路

在第一次将机器人加入群/频道时，会创建两个默认角色和三个默认策略

角色：

- 普通成员：所有用户默认属于`普通成员`角色，可以执行除管理员指令以外的所有指令
- 管理员：由于藤子没有提供获取群管理的接口，这里所谓的`管理员`为邀请机器人入群的用户，可以执行所有指令

策略：

- 管理员权限：允许管理员执行管理员指令
- 普通成员权限：允许普通成员执行非管理员指令
- 普通成员不允许执行管理员指令：阻止普通成员执行管理员指令

## 权限检查逻辑

每个群可以有多个策略，策略可分为`ALLOW`和`DENY`两种，它们的区别可看[策略动作](#策略动作)

执行权限检查时，同时检查所有策略，任意`ALLOW`条件满足则通过，任意`DENY`条件满足则禁止，`DENY`的优先级高于`ALLOW`

## 策略编辑

提供可视化界面用于编辑策略，并且提供测试按钮在正式保存策略前测试策略在给定情况下是否能触发，接下来以`普通成员不允许执行管理员指令`策略为基础，介绍各项按钮

::: details 整个界面截图

<img src="/image/webui/policy/page.webp" alt="page" />

:::

界面分为功能按钮区(1区)和可视化编辑区(2区)

## 功能按钮

- 策略选择：快捷切换当前编辑的策略
- 创建：创建一个全新的策略，当前未保存的进度会被清空
- 重置：重新编辑选择的策略，当前未保存的进度会被清空
- 保存：保存编辑好的策略
- 测试：在给定的条件下测试策略是否能够按预期运行，具体操作在下边[测试](#测试)一节
- 从其他群导入：从自己管理的其他群导入策略
- 删除：删除当前策略

## 可视化编辑

采用树形图展示策略结构，可以通过鼠标滚轮缩放以及左键进行拖动，节点类型分为三种：`根节点`、`子节点`和`规则节点`

- 根节点：策略的出发点，也就是整个树形图中最左侧的节点，支持`添加子节点`和`编辑根节点`操作
- 子节点：树杈和树叶，支持`添加子节点`、`编辑子节点`、`删除子节点`和添加`规则节点`操作
- 规则节点：树叶，代表一条具体的规则，支持`编辑规则`、`删除规则`和`添加兄弟规则`操作

### 根节点

::: details 大概长这样

<img src="/image/webui/policy/policy-root.webp" alt="policy-root" />

:::

可能会被字挡住看不清，右上角有`+`和`E`两个交互按钮

#### 编辑根节点

点击`E`按钮，弹出编辑框，可以修改根节点的名称

#### 添加子节点

点击`+`按钮，弹出编辑框，可以添加一个子节点

### 子节点

::: details 大概长这样

<img src="/image/webui/policy/policy-node.webp" alt="policy-node" />

:::

右上角有`+`和`E`两个交互按钮，右下角有红色的`-`按钮

#### 编辑子节点

点击`E`按钮，弹出编辑框，可以修改子节点的[节点类型](#子节点类型)

#### 添加子节点

点击`+`按钮，弹出编辑框，可以添加一个子节点或添加一个规则节点

#### 删除子节点

点击红色的`-`按钮，删除当前子节点及其所有子节点和规则节点

### 规则节点

::: details 大概长这样

<img src="/image/webui/policy/policy-rule.webp" alt="policy-rule" />

:::

右上角有`E`按钮，右下角有红色的`-`按钮

#### 编辑规则

点击`E`按钮，弹出编辑框，可以修改规则的对象、属性、操作符和值，具体内容见[规则节点编辑](#规则节点编辑)

#### 删除规则

点击红色的`-`按钮，删除当前规则

## 根节点编辑

### 策略动作

`ALLOW`：当所有子节点匹配时，允许执行

`DENY`：当所有子节点匹配时，拒绝执行

## 子节点编辑

### 节点类型

分为`ALL`、`ANY`、`NOT_ALL`和`NOT_ANY`四种类型

- ALL：当所有子节点和规则都匹配时，自己才匹配
- ANY：当任意子节点或规则匹配时，自己就匹配
- NOT_ALL：当任意子节点和规则不匹配时，自己就匹配(对ALL结果取反)
- NOT_ANY：当所有子节点和规则都不匹配时，自己才匹配(对ANY结果取反)

## 规则节点编辑

### 对象

分为`Resource`、`Action`、`Subject`和`Environment`四种类型，不同类型的对象具有不同的属性，不同的属性能够选择不同的操作符

### Resource.id

指代一个或一组指令，使用`:`和`.`进行分割，特别的`*`指代其后的所有指令

例如`com.diyigemt.arona:*`代表由`Arona`插件创建的所有资源

`com.diyigemt.arona:command.*`代表由`Arona`插件创建的所有指令

`com.diyigemt.arona:command.攻略`代表由`Arona`插件提供的攻略指令

目前已有的id如下表：

| id                             | 来源         | 作用                             |
|--------------------------------|------------|--------------------------------|
| *                              | 根id        | 所有id的爹                         |
| com.diyigemt.arona:*           | Arona插件    | 所有Arona插件资源的爹                  |
| com.diyigemt.arona:command.*   | Arona插件的指令 | 所有Arona插件的指令的爹                 |
| com.diyigemt.arona:command.叫我  | Arona插件的指令 | [叫我](../manual/command#昵称系列)   |
| com.diyigemt.arona:command.攻略  | Arona插件的指令 | [攻略](../manual/command#攻略系列)   |
| com.diyigemt.arona:command.塔罗牌 | Arona插件的指令 | [塔罗牌](../manual/command#塔罗牌系列) |
| buildIn.normal:*               | 内置         | 所有内置普通id的爹                     |
| buildIn.normal:command.*       | 内置         | 所有内置普通指令的爹                     |
| buildIn.normal:command.登录      | 内置         | 用于[登录webui](./login)的指令        |
| buildIn.owner:*                | 内置         | 所有内置管理员id的爹                    |
| buildIn.owner:command.*        | 内置         | 所有内置管理员指令的爹                    |
| buildIn.owner:command.绑定       | 内置         | 用于修改当前群/频道在webui中显示的名称         |

### Subject.id

目前Subject用于指代执行指令的用户，那么`Subject.id`的指向就很明确了，就是指代特定用户

### Subject.roles

目前Subject用于指代执行指令的用户，那么`Subject.roles`的指向就很明确了，就是指代用户所拥有的角色

### Environment.time

指代执行指令时的时间，格式为 HH:mm:ss，即时分秒

### Environment.date

指代执行指令时的时间，格式为 yyyy-MM-dd，即年月日

### Environment.datetime

指代执行指令时的时间，格式为 yyyy-MM-dd HH:mm:ss，即年月日时分秒

### Environment.param1

指代指令的第一个参数，如`/攻略 12-1`，那么第一个参数为`12-1`

### Environment.param2

指代指令的第二个参数

## 操作符

| 值                | 作用   |
|------------------|------|
| Equal            | =    |
| LessThan         | <    |
| GreaterThan      | \>   |
| LessThanEqual    | <=   |
| GreaterThanEqual | \>=  |
| Contains         | 包含   |
| ContainsAll      | 包含全部 |
| ContainsAny      | 包含任意 |
| IsIn             | 被包含  |
| IsChild          | 是仔   |

当然这么看可能不太好理解，下面有几个例子

`Subject.id Equal XXX`：当用户id等于`XXX`时

`Subject.id IsIn [XXX,YYY]`：当用户id是`XXX`或`YYY`时

`Subject.roles Contains 管理员`：当用户角色有`管理员`时

`Subject.roles ContainsAll [管理员,一个自定义的角色]`：当用户角色既有`管理员`又有`一个自定义的角色`时

`Environment.time LessThan 19:45:00`：检查权限时的时间小于`19:45`时

`Environment.param1 Equal 12-1`：当指令的第一个参数等于`12-1`时

`Resource.id IsIn [com.diyigemt.arona:command.攻略, com.diyigemt.arona:command.叫我]`：当指令是`/攻略`或者`/叫我`时

`Resource.id IsChild com.diyigemt.arona:command.*`：当指令Arona插件提供的指令时

### 关于IsChild

目前`IsChild`仅在对象为`Subject`，属性为`id`时有效，作用为判断一个指令`id`是否为给定的`id`的仔

一个`id`是一组由`.`、`:`和`*`以及其他任意字符组合成的字符串，`.`用于表示包名，`:`用于区分层级，`*`用于通用匹配

例如`com.diyigemt.arona:command.攻略`可分割成`com.diyigemt.arona`：表示Arona插件，和`command.攻略`：表示指令(command)下的攻略指令

例如`com.diyigemt.arona:command.*`可分割成`com.diyigemt.arona`：表示Arona插件，和`command.*`：表示所有指令(command)

例如`com.diyigemt.arona:*`可分割成`com.diyigemt.arona`：表示Arona插件，和`*`：表示插件的所有资源

回到正题，`IsChild`就是用来判断`*`是否能够包含省略下的层级的

| Resource.id                   | 用于比较的                         | 匹配 |
|-------------------------------|-------------------------------|----|
| com.diyigemt.arona:command.攻略 | com.diyigemt.arona:command.攻略 | 是  |
| com.diyigemt.arona:command.攻略 | com.diyigemt.arona:command.*  | 是  |
| com.diyigemt.arona:command.攻略 | com.diyigemt.arona:*          | 是  |
| com.diyigemt.arona:command.*  | com.diyigemt.arona:command.*  | 是  |
| com.diyigemt.arona:command.*  | com.diyigemt.arona:command.攻略 | 否  |
| com.diyigemt.arona:config.攻略  | com.diyigemt.arona:command.*  | 否  |
| com.diyigemt.arona:config.*   | com.diyigemt.arona:command.*  | 否  |
| com.diyigemt.arona:config.*   | com.diyigemt.arona:*          | 是  |

特别的，当比较值为`*`时，无论提供的`Resource.id`是什么，都会匹配


## 值

就是用于比较的值了，不同的`对象`、`属性`和`操作符`的组合下，值的类型也不同，界面上已经做好区分了

## 举个例子

### 阻止普通成员执行管理员指令

<img src="/image/webui/policy/example-1.webp" alt="example-1" />

根节点类型为`DENY`，即当条件匹配时，阻止执行指令

第一级子节点类型为`ALL`，即当所有二级节点和规则匹配时，自身匹配

二级规则，`Resource.id IsChild BuildIn.owner:*`，当指令为管理员指令时匹配

二级节点类型为`NOT_ALL`，对`ALL`结果取反

三级规则，`Subject.roles Contains role.admin`，当用户具有管理员角色时匹配

总结一下，当指令为管理员指令，且用户不具备管理员角色时，阻止指令的执行

### 在早上8点到10点之间禁止执行塔罗牌指令

<img src="/image/webui/policy/example-2.webp" alt="example-2" />

根节点类型为`DENY`，即当条件匹配时，阻止执行指令

第一级子节点类型为`ALL`，即当所有二级节点和规则匹配时，自身匹配

第一条二级规则，`Environment.time GreaterThan 08:00:00`，当时间大于8时匹配

第二条二级规则，`Environment.time LessThan 10:00:00`，当时间小于10时匹配

第三条二级规则，`Resource.id Equal com.diyigemt.arona:command.塔罗牌`，当指令为塔罗牌时匹配

总结一下，当指令为塔罗牌，且时间是早上8点到10点之间时，阻止指令的执行

## 策略的测试

在按钮功能区1中有个测试按钮，点击可弹出测试数据输入窗口

在窗口中设置好执行条件，点击确定后，可在可视化编辑区查看策略个节点和规则的匹配情况，红色为匹配失败，绿色为匹配成功

只有当根节点显示为绿色时，整个策略才会被认为是匹配的

## 举个测试的例子

### 普通成员执行管理员指令

给定测试条件：执行内置的管理员绑定指令，用户为普通用户，只拥有普通成员角色

::: details 测试条件

<img src="/image/webui/policy/test-1-1.webp" alt="test-1-1" />

<img src="/image/webui/policy/test-1-2.webp" alt="test-1-2" />

:::

测试结果：

<img src="/image/webui/policy/test-1.webp" alt="test-1" />

绑定指令的id`buildIn.owner:command.绑定`符合`Resource.id IsChild buildIn.owner:*`的条件，因此规则节点为绿色

用户的`roles`只有普通用户，不满足`Subject.roles Contains role.admin`的条件，因此规则节点为红色

二级子节点`NOT_ALL`对子节点和规则执行`ALL`后取反，因此子节点为绿色

一级子节点`ALL`下的子节点和规则节点都为绿色，因此自己也为绿色

根节点下的所有子节点和规则节点都为绿色，因此自己为绿色，代表策略匹配

根节点类型为`DENY`，当自己匹配时，阻止指令执行，因此策略匹配结果为预期的结果，阻止普通成员执行管理员指令

现在换个输入条件，让用户也具有管理员权限

<img src="/image/webui/policy/test-1-2.1.webp" alt="test-1-2.1" />

再次执行，结果如下：

<img src="/image/webui/policy/test-1.1.webp" alt="test-1.1" />

`Subject.roles Contains role.admin`的条件被满足，导致`NOT_ALL`节点匹配失败，最终导致根节点匹配失败，不满足阻止执行的规则，允许指令执行

### 在早上8点到10点之间执行塔罗牌指令

给定测试条件：执行塔罗牌指令，用户为普通用户，只拥有普通成员角色，在早上11:31:08时执行

::: details 测试条件

<img src="/image/webui/policy/test-2-1.webp" alt="test-2-1" />

<img src="/image/webui/policy/test-2-2.webp" alt="test-2-2" />

<img src="/image/webui/policy/test-2-3.webp" alt="test-2-2" />

:::

测试结果：

<img src="/image/webui/policy/test-2.webp" alt="test-2" />