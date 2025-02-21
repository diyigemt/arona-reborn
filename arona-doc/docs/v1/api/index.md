# BA bot攻略数据公开计划

该api最初是作为[arona bot](https://github.com/diyigemt/arona)的服务而开发出来的，现在由于tx疯狂打压mirai系bot的生存空间，看不到活着的希望，于是决定公开，希望能帮助到其他类型的bot

具体效果可以看站里的[功能介绍](/v1/command/manual#%E5%AD%A6%E7%94%9F%E4%B8%8E%E4%B8%BB%E7%BA%BF%E5%9C%B0%E5%9B%BE%E6%94%BB%E7%95%A5%E7%B3%BB%E5%88%97)

## 2025-02-21更新

入参新增`r18`, 当传入`r18=0`时在返回结果中过滤掉nsfw的结果, 由于是人工打标, 有遗漏的联系我加上

## 2025-01-31更新

更新了模糊匹配的算法, 更新了接口参数, 提供3种模糊搜索方式选择, 在模糊搜索情况下返回值也会带上结果数据了

## 2023-11-23更新

由于之前用 node 写的后端扛不住了，所以新写了一个，新后端的api基址已经改为 https://arona.diyigemt.com/api/v2

并且返回格式也进行了更改，目前的情况是对 v1 的接口做了个兼容，将会在3个月后，即 **2024-02-23** 停止 v1 api 的运行

v1 的文档放访问这里: [v1文档](/v1/api/api-v1)

## 前言

1. 所有图片均来源于网络，在非wiki处收集来的图片已经打上出处，希望各位使用时不要将出处抹去
2. 所有图片均为人工整理，因此出现错漏可以联系我
   - [gmail](qwe1355247243@gmail.com)
   - [github](https://github.com/diyigemt/arona)
   - [bilibili](https://space.bilibili.com/6690298)
   - [nga](https://nga.178.com/nuke.php?func=ucp&uid=42164110)
3. 使用本 API 是你个人的行为，产生的任何问题都与我无关
4. 在使用过程中如果出现图片错漏/无法发出等情况，可以去[腾讯文档的表格](https://docs.qq.com/sheet/DVGNTT3hCVUJKVHZP?tab=BB08J2)反馈

api 基址为 https://arona.diyigemt.com/api/v2

所有接口返回值使用统一数据结构进行包装，结构如下

| 参数名     | 数据类型   | 是否必须 | 说明            |
|---------|--------|------|---------------|
| code    | Int    | 是    | 处理结果代码        |
| message | String | 是    | 处理结果描述        |
| data    | T      | 否    | 具体结果, 数据结构不确定 |

例如:

```json
{
  "code": 200,
  "message": "OK",
  "data": null
}
```

当`code`为 200 时，`message`固定为"OK"。其他情况下，不同接口定义略有不同。

## 攻略系列

### 主线·学生·活动等攻略

实现效果可看[功能介绍](/v1/command/manual#%E5%AD%A6%E7%94%9F%E4%B8%8E%E4%B8%BB%E7%BA%BF%E5%9C%B0%E5%9B%BE%E6%94%BB%E7%95%A5%E7%B3%BB%E5%88%97)

请求

```shell
GET https://arona.diyigemt.com/api/v2/image
```

请求参数

| 参数名    | 数据类型   | 是否必须 | 默认值      | 说明                                          |
|--------|--------|------|----------|---------------------------------------------|
| name   | string | 是    |          | 要查找的攻略名称                                    |
| size   | Int    | 否    | 8        | 返回数据长度, 不一定严格遵守, 但数据不会超过这个值 1 <= size <= 20 |
| method | Int    | 否    | 1        | 模糊搜索方法选择, 具体可看[下面](#模糊搜索方法)                 |
| rate   | Int    | 否    | size / 2 | 当method选择混合时, 方法一返回结果数在总返回结果数的中的数量          |
| r18    | Int    | 否    | 1        | 0和非0值, 当传入0时在返回结果中过滤nsfw的结果, 注意！过滤会导致返回数量不能满足size和rate的限制！|

返回值

| 参数名     | 数据类型                 | 说明  |
|---------|----------------------|-----|
| code    | Int                  |     |
| data    | ResultList[] \| NULL | 结果集 |
| message | String               |     |

ResultList

| 参数名     | 数据类型   | 说明        |
|---------|--------|-----------|
| name    | Int    | 对应名称      |
| hash    | String | 图片md5结果   |
| content | String | 图片路径/结果文本 |
| type    | String | 结果类型      |

其中`type`字段目前有两种类型

1. file: 此时`content`中的内容为图片的`cdn`地址
2. plain: 此时`content`中的内容为纯文本，字面意思

特别的，对于`file`类型的结果，其最终地址为

```kotlin
"https://arona.cdn.diyigemt.com/image${it.content}"
"https://arona.cdn.diyigemt.com/image/s${it.content}"
```

包含`/s/`的路径中的图片大小必定小于`4M`，可以满足QQ频道图片的大小限制

当`name`没有精确对应结果时, 接口会返回模糊查询结果, 此时`code`必定为101, `data`有两种可能,`list`或者`null`
- 为`null`表明连模糊查询结果都没有
- 为`list`时,`list`的`len`为 1 <= `len` <= size(default: 8)

可以通过`data`存在与否以及`list`的`len`判断结果类型

当为精确匹配时,`data`为`list`且其`len`必为1, 此时可通过`item`的`hash`判断是否有更新, 并通过`path`获取文件地址

精确匹配查询示例

```bash
GET https://arona.diyigemt.com/api/v2/image?name=国际服未来视
```

精确匹配返回示例

```json
{
  "code": 200,
  "message": "OK",
  "data": [
    {
      "name": "国际服未来视",
      "hash": "2cf82b1c84a498aca82121ed10ce7748",
      "content": "/some/国际服未来视.png",
      "type": "file"
    }
  ]
}
```

此时文件地址为 `https://arona.cdn.diyigemt.com/image/some/国际服未来视.png` 和 `https://arona.cdn.diyigemt.com/image/s/some/国际服未来视.png`

模糊查询结果示例

```bash
GET https://arona.diyigemt.com/api/v2/image?name=国际服&size=4
```

模糊查询返回示例

```json
{
  "code": 101,
  "message": "Fuzzy Search",
  "data": [
    {
      "name": "国际服人权",
      "hash": "8c28e093cb184555bd4a094be5aa5bdf",
      "content": "/some/日服人权.png",
      "type": "file"
    },
    {
      "name": "国际服未来视",
      "hash": "b6cd713afdb29e8249cbea66441de63e",
      "content": "/some/国际服未来视.png",
      "type": "file"
    },
    {
      "name": "国际服竞技场-1",
      "hash": "2fce6f88fbc106751c02a0224ea77edc",
      "content": "/some/国际服竞技场-1.png",
      "type": "file"
    },
    {
      "name": "国际服火力演习",
      "hash": "251baad47b9d8ce1a044fceab94766e5",
      "content": "/some/国际服火力演习.png",
      "type": "file"
    }
  ]
}
```

### 模糊搜索方法

| 类型   | 入参       |
|------|----------|
| jech | method=1 |
| 暴力匹配 | method=2 |
| 混合   | method=3 |

#### jech

基于[JustEnoughCharacters](https://github.com/Towdium/JustEnoughCharacters)中的[PinIn库](https://github.com/Towdium/PinIn)
来进行匹配

mc玩家对此应该并不陌生, 算法优点是在字音顺序严格正确的情况下具有良好的匹配度, 缺点是在间隔情况下匹配结果不佳

#### 暴力匹配

遍历输入和目标的每个字以及拼音, 根据匹配数量得到加权值并排序

#### 混合

综合以上两种算法的结果, 选出匹配程度最高的值合并返回

#### 性能测试结果

在给定的100个经常进入模糊匹配的查询中, 三种方法的匹配准确度如下

| 类型   | 匹配数量 |
|------|------|
| jech | 68   |
| 暴力匹配 | 75   |
| 混合   | 89   |

看起来是混合模式匹配最佳, 但是没有长期测试过, 所谓的`100个经常`也不一定真的是`经常`, 具体要怎么调用就看各位开发者了

### cdn的使用

::: danger

如你所见, 文件资源是部署在cdn上的, 所以希望你能读完下面的话

:::

为了加快响应速度, 所有文件资源均部署在国内cdn服务上, 前缀为`https://arona.cdn.diyigemt.com/image`

由于cdn不是白嫖的, **希望**各位开发者善用`hash`字段

理想的情况是, 获取请求结果->检查`hash`是否变更, 如果变更则下载新资源, 否则继续使用旧资源

`hash`采用**md5**进行计算, 可以使用数据库缓存上次请求的结果, 也可以每次都计算一次

### 默认情况

| name      | 说明              |
|-----------|-----------------|
| 国服未来视     | 朝夕的未来视          |
| 国服活动      | 始终指向国服当期活动      |
| 国际服未来视    | 猫佬的未来视          |
| 国际服总力     | 始终指向国际服当期总力     |
| 国际服火力演习   | 始终指向国际服当期火力演习   |
| 国际服活动     | 始终指向国际服当期活动     |
| 日服总力      | 始终指向日服当期总力      |
| 日服大决战     | 始终指向日服当期大决战     |
| 日服火力演习    | 始终指向日服当期火力演习    |
| 日服活动      | 始终指向日服当期活动      |

::: warning

再次强调, 所有图片都是人工整理, 难免会有错漏和更新不及时的情况, 请见谅

:::
