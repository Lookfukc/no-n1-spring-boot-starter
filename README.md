# no-n1-spring-boot-starter

用于解决 Java 应用中 N+1 查询问题的轻量级 Spring Boot Starter。

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lookfukc/no-n1-spring-boot-starter.svg)](https://central.sonatype.com/search?q=io.github.lookfukc%20no-n1)
[![License](https://img.shields.io/github/license/lookfukc/no-n1-spring-boot-starter)](LICENSE)

## 目录

- [版本要求](#版本要求)
- [项目简介](#项目简介)
- [核心特性](#核心特性)
- [快速开始](#快速开始)
  - [Maven 依赖](#maven-依赖)
  - [基础用法](#基础用法)
  - [MyBatis Plus：使用 withRelationList](#mybatis-plus-使用-withrelationlist)
  - [MongoDB / 动态查询：使用 fromMaps](#mongodb--动态查询使用-frommaps)
- [属性复制器选择](#属性复制器选择)
  - [复制器对比](#复制器对比)
  - [使用 MapStruct（推荐）](#使用-mapstruct推荐)
  - [使用 SpringBeanUtilsCopier](#使用-springbeanutilscopier)
  - [使用 HutoolBeanCopier](#使用-hutoolbeancopier)
  - [使用 JdkBeansCopier](#使用-jdkbeanscopier)
  - [使用 DefaultBeanCopier（默认）](#使用-defaultbeancopier默认)
- [并行查询](#并行查询)
  - [性能对比](#性能对比)
  - [基础并行查询](#基础并行查询)
  - [全链路优化（fast）](#全链路优化fast)
  - [使用自定义线程池](#使用自定义线程池)
  - [使用场景建议](#使用场景建议)
- [大数据量处理](#大数据量处理)
  - [查询分批（queryBatchSize）](#查询分批querybatchsize)
  - [分页处理（pageSize / buildPage）](#分页处理pagesize--buildpage)
- [多层嵌套查询](#多层嵌套查询)
  - [使用场景](#使用场景)
  - [方式一：withNested（推荐）](#方式一withnested推荐)
  - [方式二：AssemblyContext + 预加载数据](#方式二assemblycontext--预加载数据)
  - [两种方式对比](#两种方式对比)
  - [withNested 执行流程](#withnested-执行流程)
  - [AssemblyContext API](#assemblycontext-api)
  - [单个对象转换](#单个对象转换)
  - [批量添加共享数据](#批量添加共享数据)
- [树形结构构建](#树形结构构建)
  - [使用场景-1](#使用场景-1)
  - [基础用法](#基础用法-1)
  - [高级用法：排序与过滤](#高级用法排序与过滤)
  - [指定根节点](#指定根节点)
  - [树工具类（TreeUtils）](#树工具类treeutils)
  - [RelationAssembler + TreeBuilder 组合使用](#relationassembler--treebuilder-组合使用)
  - [使用实例：部门树查询](#使用实例部门树查询)
- [日志与调试](#日志与调试)
  - [日志级别说明](#日志级别说明)
  - [开启 TRACE 日志](#开启-trace-日志)
  - [TRACE 日志输出示例](#trace-日志输出示例)
- [完整示例](#完整示例)
  - [实体类](#实体类)
  - [VO 类](#vo-类)
  - [Repository 接口](#repository-接口)
  - [MapStruct Mapper](#mapstruct-mapper)
  - [Service 实现](#service-实现)
  - [Controller](#controller)
- [性能基准](#性能基准)
  - [查询性能对比](#查询性能对比)
  - [属性复制器性能对比](#属性复制器性能对比-1)
- [常见问题](#常见问题)
- [API 参考](#api-参考)
  - [RelationAssembler](#relationassembler)
  - [withRelation 参数说明](#withrelation-参数说明)
  - [withRelationList 参数说明](#withrelationlist-参数说明)
  - [withNested 参数说明](#withnested-参数说明)
  - [NestedBuilder 配置方法](#nestedbuilder-配置方法)
  - [Builder 配置方法](#builder-配置方法)
  - [TreeBuilder](#treebuilder-1)
  - [TreeBuilder.Builder 配置方法](#treebuilderbuilder-配置方法)
  - [TreeUtils 工具方法](#treeutils-工具方法)
  - [AssemblyContext](#assemblycontext-1)
  - [BeanCopier](#beancopier)
- [更新日志](#更新日志)
- [仓库地址](#仓库地址)
- [许可证](#许可证)
- [作者](#作者)

---

## 版本要求

| 依赖 | 最低版本 | 推荐版本 | 说明 |
|------|----------|----------|------|
| **Java** | 11+ | 11 或 17+ | 本项目编译和运行需要 Java 11 或更高版本 |
| **Spring Boot** | 2.3+ / 3.0+ | 2.7.x 或 3.2+ | 兼容 Spring Boot 2.x 和 3.x |
| **Spring Framework** | 5.2+ / 6.0+ | 5.3.x 或 6.1+ | 分别对应 Spring Boot 2.x 和 3.x |

> **兼容性说明**：
> - 本库核心功能不依赖 `javax.*` 或 `jakarta.*` 命名空间，因此同时兼容 Spring Boot 2.x 和 3.x
> - Spring Boot 2.x 使用 `javax.*`，Spring Boot 3.x 使用 `jakarta.*`，与本库无关
> - 自动配置通过 `spring.factories`（Boot 2.x）和 `AutoConfiguration.imports`（Boot 3.x）双重支持

## 项目简介

N+1 查询是数据库访问中常见的性能问题，即执行了多次数据库查询而不是单次批量查询。本库提供了一种优雅的解决方案，通过批量查询关联对象并将其组装到 VO 中，将 O(n) 的查询复杂度降低到 O(1)，显著提升性能。

**核心思想：**

```
传统方式（N+1 问题）：          本库方式（批量查询）：
查询订单列表（1 次）             查询订单列表（1 次）
├─ 订单1 → 查询用户（1 次）      提取所有 userId → [1,2,3,5,8]
├─ 订单2 → 查询用户（1 次）      批量查询用户（1 次）→ 命中全部
├─ 订单3 → 查询用户（1 次）      组装映射 → 完成
├─ 订单4 → 查询用户（1 次）
└─ 订单5 → 查询用户（1 次）
= 共 6 次数据库查询               = 共 2 次数据库查询
```

## 核心特性

- **零依赖**：核心功能基于 Java 原生反射实现，无需额外依赖
- **灵活适配**：支持多种属性复制策略（MapStruct、Spring BeanUtils、Hutool、JDK 原生、自定义实现）
- **高性能**：支持多个关联对象的并行查询，以及全链路并行优化（查询并行 + VO 转换并行）
- **类型安全**：基于泛型设计，编译期进行类型检查
- **易于集成**：开箱即用的 Spring Boot Starter，自动配置
- **多层嵌套支持**：通过 withNested 和 AssemblyContext 支持复杂的嵌套对象组装
- **大数据量支持**：内置查询分批（queryBatchSize）和分页处理（pageSize），防止 SQL 过长和内存溢出
- **树形结构构建**：TreeBuilder 支持将扁平数据转换为树形结构，支持排序、过滤、层级、路径等
- **调试友好**：内置 TRACE 级别日志，可查看完整的组装交互过程

## 快速开始

### Maven 依赖

| 版本 | Spring Boot 兼容性 | 适用场景 |
|------|-------------------|----------|
| **1.2.0** | 2.3+ 和 3.x | 推荐使用，支持多层嵌套、大数据量处理、全链路优化 |
| **1.1.0** | 2.3+ 和 3.x | 兼容所有版本 |
| **1.0.0** | 仅 3.x | 早期版本，仅 Spring Boot 3 |

**Maven：**

```xml
<!-- no-n1-spring-boot-starter -->
<dependency>
    <groupId>io.github.lookfukc</groupId>
    <artifactId>no-n1-spring-boot-starter</artifactId>
    <version>1.2.0</version>
</dependency>

<!-- Spring Boot AutoConfigure（provided 作用域，不会传递，需确保项目中存在） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-autoconfigure</artifactId>
</dependency>
```

> **说明**：`spring-boot-autoconfigure` 在本项目中为 `provided` 作用域，不会传递给消费者。大多数 Spring Boot 项目通过 `spring-boot-starter-web` 或 `spring-boot-starter-data-jpa` 等已间接引入，无需重复添加。如果你的项目中没有这些 starter，需要手动添加。

**Gradle：**

```groovy
implementation 'io.github.lookfukc:no-n1-spring-boot-starter:1.2.0'
// Spring Boot AutoConfigure（通常通过其他 starter 已引入，无需重复添加）
// implementation 'org.springframework.boot:spring-boot-autoconfigure'
```

**可选依赖（根据需要添加）：**

| 依赖 | 适用的复制器 | 说明 |
|------|-------------|------|
| `org.mapstruct:mapstruct` | MapStruct | 编译期代码生成，性能最高，推荐使用 |
| `cn.hutool:hutool-all` | HutoolBeanCopier | 已使用 Hutool 的项目可选 |

### 基础用法

#### 简单示例：单个关联对象

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    public List<OrderVO> getOrderList() {
        List<Order> orders = orderRepository.findAll();

        // 批量查询用户信息并组装到 OrderVO
        return RelationAssembler.from(orders, OrderVO.class)
            .withRelation(
                Order::getUserId,                           // 提取用户 ID
                ids -> userRepository.findAllById(ids),     // 批量查询用户
                User::getId,                                // 获取用户 ID
                OrderVO::setUser                            // 设置用户到 VO
            )
            .build();
    }
}
```

#### 使用实例：订单查询

典型的电商订单查询场景，包含用户、商品、店铺等关联信息。

**查询需求：**
- 查询订单列表
- 关联查询用户信息
- 关联查询商品信息
- 关联查询店铺信息
- 支持并行查询提升性能

**实现代码：**

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ShopRepository shopRepository;
    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    private final ProductMapper productMapper;
    private final ShopMapper shopMapper;

    /**
     * 查询订单列表（串行版本）
     */
    public List<OrderVO> getOrderList(OrderQueryDTO dto) {
        List<Order> orders = orderRepository.findByDto(dto);

        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .withRelation(
                Order::getProductId,
                ids -> productRepository.findAllById(ids),
                Product::getId,
                productMapper::toVO,
                OrderVO::setProduct
            )
            .withRelation(
                Order::getShopId,
                ids -> shopRepository.findAllById(ids),
                Shop::getId,
                shopMapper::toVO,
                OrderVO::setShop
            )
            .build();
    }

    /**
     * 查询单个订单详情
     */
    public OrderVO getOrderDetail(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在"));

        return RelationAssembler.from(order, OrderVO.class, orderMapper::toVO)
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .withRelation(
                Order::getProductId,
                ids -> productRepository.findAllById(ids),
                Product::getId,
                productMapper::toVO,
                OrderVO::setProduct
            )
            .withRelation(
                Order::getShopId,
                ids -> shopRepository.findAllById(ids),
                Shop::getId,
                shopMapper::toVO,
                OrderVO::setShop
            );
    }
}
```

#### MyBatis Plus：使用 withRelationList

如果项目中使用 MyBatis Plus，批量查询方法通常接收 `List` 而非 `Set`（如 `selectBatchIds`），此时使用 `withRelationList` 代替 `withRelation`：

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProductMapper productMapper;

    public List<OrderVO> getOrderList() {
        List<Order> orders = orderMapper.selectList(null);

        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            // 使用 withRelationList：queryFunction 接收 List<I>
            .withRelationList(
                Order::getUserId,
                ids -> userMapper.selectBatchIds(ids),     // MyBatis Plus: selectBatchIds(List<Long>)
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .withRelationList(
                Order::getProductId,
                ids -> productMapper.selectBatchIds(ids),   // MyBatis Plus: selectBatchIds(List<Long>)
                Product::getId,
                productMapper::toVO,
                OrderVO::setProduct
            )
            .build();
    }
}
```

> **`withRelation` 与 `withRelationList` 的区别**：唯一的区别是 `queryFunction` 的参数类型。`withRelation` 接收 `Set<I>`（JPA 的 `findAllById`），`withRelationList` 接收 `List<I>`（MyBatis Plus 的 `selectBatchIds`）。其他参数完全一致。

#### MongoDB / 动态查询：使用 fromMaps

当数据源不是 Java Bean 而是 `Map<String, Object>`（如 MongoDB Document、动态 SQL 查询结果）时，使用 `fromMaps`：

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.Map;

@Service
public class OrderService {

    @Autowired
    private UserRepository userRepository;

    /**
     * 从 MongoDB Document 或动态查询结果组装 VO
     */
    public List<OrderVO> getOrdersFromMongo() {
        // MongoDB 查询返回 List<Map<String, Object>>
        List<Map<String, Object>> mapList = mongoTemplate.findAll(Document.class, "orders");

        return RelationAssembler.fromMaps(mapList, OrderVO.class)
            // extractor 接收 Map，通过 key 获取关联 ID
            .withRelation(
                map -> (Long) map.get("userId"),
                ids -> userRepository.findAllById(ids),
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .build();
    }
}
```

> **说明**：`fromMaps` 默认通过反射将 Map 中 key 与 VO 字段名匹配的值写入 VO。如果需要自定义转换逻辑（如类型转换、字段映射），可以使用 `fromMaps(mapList, OrderVO.class, customConverter)` 传入自定义转换函数。

本库提供了多种属性复制实现，请根据项目实际情况选择：

### 复制器对比

| 复制器 | 依赖 | 性能 | 适用场景 |
|--------|------|------|----------|
| MapStruct | mapstruct | 最高 | 编译期生成代码，性能接近手写，**推荐使用** |
| SpringBeanUtilsCopier | Spring | 中等 | 使用 Spring 框架的 BeanUtils，适合 Spring 项目 |
| HutoolBeanCopier | hutool | 中等 | 使用 Hutool 的 BeanUtil，适合已使用 Hutool 的项目 |
| JdkBeansCopier | 无 | 中等 | 使用 JDK 内省机制，纯 Java 方案 |
| DefaultBeanCopier | 无 | 中等 | 内置反射实现，带字段缓存，默认使用 |

### 使用 MapStruct（推荐）

MapStruct 在编译期生成映射代码，性能接近手写代码。

**第一步：添加 MapStruct 依赖**

```xml
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>1.5.5.Final</version>
</dependency>
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct-processor</artifactId>
    <version>1.5.5.Final</version>
    <scope>provided</scope>
</dependency>
```

**第二步：定义 Mapper 接口**

```java
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderVO toVO(Order order);
    List<OrderVO> toVOList(List<Order> orders);
}

@Mapper(componentModel = "spring")
public interface UserMapper {
    UserVO toVO(User user);
}

@Mapper(componentModel = "spring")
public interface ProductMapper {
    ProductVO toVO(Product product);
}
```

**第三步：在 Service 中使用**

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProductMapper productMapper;

    public List<OrderVO> getOrderListWithDetails() {
        List<Order> orders = orderRepository.findAll();

        // 使用 MapStruct 转换器
        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .withRelation(
                Order::getUserId,                           // 提取用户 ID
                ids -> userRepository.findAllById(ids),     // 批量查询用户
                User::getId,                                // 获取用户 ID
                userMapper::toVO,                           // 转换 User 为 UserVO
                OrderVO::setUser                            // 设置到 OrderVO
            )
            .withRelation(
                Order::getProductId,                        // 提取商品 ID
                ids -> productRepository.findAllById(ids),  // 批量查询商品
                Product::getId,                             // 获取商品 ID
                productMapper::toVO,                        // 转换 Product 为 ProductVO
                OrderVO::setProduct                         // 设置到 OrderVO
            )
            .build();
    }
}
```

### 使用 SpringBeanUtilsCopier

对于 Spring Boot 应用，直接使用 Spring 内置的 BeanUtils：

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import io.github.lookfukc.non1.copier.SpringBeanUtilsCopier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    public List<OrderVO> getOrderListWithDetails() {
        List<Order> orders = orderRepository.findAll();

        // 使用 Spring BeanUtils 属性复制器
        return RelationAssembler.from(orders, OrderVO.class, SpringBeanUtilsCopier.of())
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .withRelation(
                Order::getProductId,
                ids -> productRepository.findAllById(ids),
                Product::getId,
                productMapper::toVO,
                OrderVO::setProduct
            )
            .build();
    }
}
```

### 使用 HutoolBeanCopier

如果项目已引入 Hutool：

**添加依赖**

```xml
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-all</artifactId>
    <version>5.8.20</version>
</dependency>
```

**使用示例**

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import io.github.lookfukc.non1.copier.HutoolBeanCopier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    public List<OrderVO> getOrderListWithDetails() {
        List<Order> orders = orderRepository.findAll();

        // 使用 Hutool 属性复制器
        return RelationAssembler.from(orders, OrderVO.class, HutoolBeanCopier.of())
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .withRelation(
                Order::getProductId,
                ids -> productRepository.findAllById(ids),
                Product::getId,
                productMapper::toVO,
                OrderVO::setProduct
            )
            .build();
    }
}
```

### 使用 JdkBeansCopier

使用 JDK 内省机制的纯 Java 方案：

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import io.github.lookfukc.non1.copier.JdkBeansCopier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    public List<OrderVO> getOrderListWithDetails() {
        List<Order> orders = orderRepository.findAll();

        // 使用 JDK 内省属性复制器
        return RelationAssembler.from(orders, OrderVO.class, JdkBeansCopier.of())
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .withRelation(
                Order::getProductId,
                ids -> productRepository.findAllById(ids),
                Product::getId,
                productMapper::toVO,
                OrderVO::setProduct
            )
            .build();
    }
}
```

### 使用 DefaultBeanCopier（默认）

不指定转换器时默认使用 `DefaultBeanCopier`，基于反射和字段缓存实现属性复制，适用于源实体和 VO 字段名、类型一致的场景：

```java
// 不指定转换器，默认使用 DefaultBeanCopier
RelationAssembler.from(orders, OrderVO.class)
    .withRelation(
        Order::getUserId,
        ids -> userRepository.findAllById(ids),
        User::getId,
        OrderVO::setUser
    )
    .build();
```

## 并行查询

当需要查询多个独立的关联对象时，启用并行执行可以提升性能。

### 性能对比

假设需要查询 3 个关联对象，每个查询耗时 10ms：

| 模式 | 耗时 | 说明 |
|------|------|------|
| 串行查询 | 10ms + 10ms + 10ms = 30ms | 查询按顺序依次执行 |
| 并行查询 | max(10ms, 10ms, 10ms) ≈ 10ms | 查询同时执行 |

### 基础并行查询

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ShopMapper shopMapper;

    public List<OrderVO> getOrderListWithDetailsParallel() {
        List<Order> orders = orderRepository.findAll();

        // 启用并行查询
        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .parallel()                                     // 启用并行查询
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),    // 查询 1：用户信息
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .withRelation(
                Order::getProductId,
                ids -> productRepository.findAllById(ids),  // 查询 2：商品信息
                Product::getId,
                productMapper::toVO,
                OrderVO::setProduct
            )
            .withRelation(
                Order::getShopId,
                ids -> shopRepository.findAllById(ids),     // 查询 3：店铺信息
                Shop::getId,
                shopMapper::toVO,
                OrderVO::setShop
            )
            .build();
        // 以上 3 个查询会并行执行，总耗时约为单个查询的耗时
    }
}
```

### 全链路优化（fast）

当数据量较大时，可以使用 `fast()` 模式，同时开启**查询并行**和**VO 转换并行**。

**执行流程：**

```
parallel() 模式：                       fast() 模式：
┌──────────────────────────┐            ┌──────────────────────────┐
│ 并行查询（3个关联同时执行）│            │ 并行查询（3个关联同时执行）│
│  ↓ 串行转换 VO            │            │  ↓ 并行转换 VO（按CPU核心分片）│
│  ↓ 串行组装关联            │            │  ↓ 并行组装关联            │
└──────────────────────────┘            └──────────────────────────┘
```

**使用示例：**

```java
public List<OrderVO> getOrderListFast() {
    List<Order> orders = orderRepository.findAll();

    // 启用全链路优化
    return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
        .fast()  // 查询并行 + VO 转换并行
        .withRelation(
            Order::getUserId,
            ids -> userRepository.findAllById(ids),
            User::getId,
            userMapper::toVO,
            OrderVO::setUser
        )
        .withRelation(
            Order::getProductId,
            ids -> productRepository.findAllById(ids),
            Product::getId,
            productMapper::toVO,
            OrderVO::setProduct
        )
        .withRelation(
            Order::getShopId,
            ids -> shopRepository.findAllById(ids),
            Shop::getId,
            shopMapper::toVO,
            OrderVO::setShop
        )
        .build();
}
```

> **注意**：`fast()` 模式下 converter 必须是线程安全的（MapStruct 生成的代码天然线程安全）。

### 使用自定义线程池

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.concurrent.Executor;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ThreadPoolTaskExecutor asyncExecutor;  // Spring 配置的线程池

    public List<OrderVO> getOrderListWithCustomExecutor() {
        List<Order> orders = orderRepository.findAll();

        // 使用自定义线程池进行并行查询
        Executor executor = asyncExecutor.getThreadPoolExecutor();
        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .parallel(executor)                              // 指定线程池
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .withRelation(
                Order::getProductId,
                ids -> productRepository.findAllById(ids),
                Product::getId,
                productMapper::toVO,
                OrderVO::setProduct
            )
            .build();
    }

    /**
     * fast() 模式也支持自定义线程池
     */
    public List<OrderVO> getOrderListFastWithCustomExecutor() {
        List<Order> orders = orderRepository.findAll();

        Executor executor = asyncExecutor.getThreadPoolExecutor();
        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .fast(executor)  // 指定线程池的全链路优化
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .withRelation(
                Order::getProductId,
                ids -> productRepository.findAllById(ids),
                Product::getId,
                productMapper::toVO,
                OrderVO::setProduct
            )
            .build();
    }
}
```

### 使用场景建议

| 场景 | 是否推荐并行 | 说明 |
|------|-------------|------|
| 单个关联对象 | 不推荐 | 线程调度开销大于收益 |
| 2-3 个关联对象 | 推荐 | 性能提升明显 |
| 3 个以上关联对象 | 强烈推荐 | 并行优势最大化 |
| 查询耗时短（<1ms） | 可选 | 性能提升有限 |
| 查询耗时长（>10ms） | 推荐 | 显著减少总耗时 |
| 数据量大（>1万条）+ 多关联 | 推荐 fast() | 全链路并行，充分利用多核 |

## 大数据量处理

当关联 ID 数量很大或源数据量很大时，本库提供了分批查询和分页处理两种策略。

### 查询分批（queryBatchSize）

当关联 ID 数量超过阈值时，自动拆分为多次查询，避免数据库 `IN` 子句过长。

**使用示例：**

```java
// 10 万个 ID，batchSize=1000 时，会拆分为 100 次查询再合并结果
return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
    .queryBatchSize(1000)  // 每批最多 1000 个 ID
    .withRelation(
        Order::getUserId,
        ids -> userRepository.findAllById(ids),  // 内部自动分批调用
        User::getId,
        userMapper::toVO,
        OrderVO::setUser
    )
    .build();

// 使用默认分批大小（1000）
return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
    .queryBatchSize()  // 等同于 queryBatchSize(1000)
    .withRelation(...)
    .build();
```

**分批执行过程：**

```
10万个 ID，batchSize=1000：
├─ 批次 1/100：ID[1..1000]   → 查询 → 合并
├─ 批次 2/100：ID[1001..2000] → 查询 → 合并
├─ ...
└─ 批次 100/100：ID[99001..100000] → 查询 → 合并
= 最终结果：全部合并后构建映射
```

### 分页处理（pageSize / buildPage）

当源数据量很大时，可以通过分页处理降低峰值内存占用。

**方式一：自动分页（内部合并结果）**

```java
// 源数据超过 50000 条时，每 10000 条分一页处理
return RelationAssembler.from(bigOrderList, OrderVO.class, orderMapper::toVO)
    .pageSize(10000)  // 每页 10000 条
    .withRelation(
        Order::getUserId,
        ids -> userRepository.findAllById(ids),
        User::getId,
        userMapper::toVO,
        OrderVO::setUser
    )
    .build();

// 使用默认分页大小（10000）
return RelationAssembler.from(bigOrderList, OrderVO.class, orderMapper::toVO)
    .pageSize()  // 等同于 pageSize(10000)
    .withRelation(...)
    .build();
```

**方式二：流式分页（逐页回调，峰值内存只占一页）**

适用于数据导出、批量写入等场景，避免一次性加载所有结果到内存。

```java
// 流式分页：每处理完一页就回调
RelationAssembler.from(bigOrderList, OrderVO.class, orderMapper::toVO)
    .pageSize(5000)  // 每页 5000 条
    .withRelation(
        Order::getUserId,
        ids -> userRepository.findAllById(ids),
        User::getId,
        userMapper::toVO,
        OrderVO::setUser
    )
    .buildPage(pageResult -> {
        // 每页处理完回调，峰值内存只占一页
        // 适用于：写入文件、发送到消息队列、批量插入等
        exportService.export(pageResult);
    });

// 如果未设置 pageSize，buildPage 默认每页 10000 条
RelationAssembler.from(bigOrderList, OrderVO.class, orderMapper::toVO)
    .withRelation(...)
    .buildPage(pageResult -> {
        // 默认每页 10000 条
    });
```

**分页 + 分批组合使用：**

```java
return RelationAssembler.from(hugeList, OrderVO.class, orderMapper::toVO)
    .pageSize(10000)         // 源数据分页：每页 10000 条
    .queryBatchSize(1000)    // 查询分批：每批最多 1000 个 ID
    .parallel()              // 并行查询
    .withRelation(
        Order::getUserId,
        ids -> userRepository.findAllById(ids),
        User::getId,
        userMapper::toVO,
        OrderVO::setUser
    )
    .withRelation(
        Order::getProductId,
        ids -> productRepository.findAllById(ids),
        Product::getId,
        productMapper::toVO,
        OrderVO::setProduct
    )
    .build();
```

## 多层嵌套查询

当需要处理多层嵌套的关联对象时，`RelationAssembler` 提供了 `withNested` 和 `withSharedData` 两种方式。

**嵌套层数说明：** 本库不限制嵌套层数。层数不影响执行方式，实际业务中 2-5 层嵌套完全没有问题。

### 使用场景

```
Order (订单)
├── User (用户)              - 简单 1 层关联
├── Product (商品)           - 简单 1 层关联
└── ShippingInfo (物流信息)   - 2 层嵌套关联
    ├── Sender (发件人)       - 需要单独查询
    └── Receiver (收件人)     - 需要单独查询
```

更复杂的场景（3 层嵌套）：

```
Order (订单)
├── User (用户)
└── ShippingInfo (物流)
    ├── Sender (发件人)
    └── Receiver (收件人)
        ├── Province (省份)   - 第 3 层
        └── City (城市)       - 第 3 层
```

---

### 方式一：withNested（推荐）

**适用场景：** 绝大多数嵌套场景

**特点：**
- 代码简洁：声明式配置，库内部自动处理 ID 提取、批量查询和对象组装
- 性能优秀：同层查询自动并行，跨层查询自动按依赖顺序执行
- 易于理解：层级关系一目了然

**2 层嵌套示例：**

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ShippingInfoRepository shippingInfoRepository;
    private final ContactRepository contactRepository;
    private final OrderMapper orderMapper;
    private final ShippingInfoMapper shippingInfoMapper;

    /**
     * 查询订单列表（withNested）
     *
     * 执行流程：
     * 1. 并行查询：User、Product、ShippingInfo（第1层）
     * 2. 从 ShippingInfo 提取 ID，并行查询：Sender、Receiver（第2层）
     * 3. 自动组装所有层级关系
     */
    public List<OrderVO> getOrderListWithShipping() {
        List<Order> orders = orderRepository.findAll();

        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .parallel()
            // 第1层：用户信息
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                OrderVO::setUser
            )
            // 第1层：商品信息
            .withRelation(
                Order::getProductId,
                ids -> productRepository.findAllById(ids),
                Product::getId,
                OrderVO::setProduct
            )
            // 第1层 → 第2层：物流信息 → 联系人
            .withNested(
                Order::getShippingInfoId,
                ids -> shippingInfoRepository.findAllById(ids),
                ShippingInfo::getId,
                shippingInfoMapper::toVO,
                OrderVO::setShippingInfo,
                nested -> nested
                    // 第2层：发件人
                    .withRelation(
                        ShippingInfo::getSenderId,
                        ids -> contactRepository.findAllById(ids),
                        Contact::getId,
                        ShippingInfoVO::setSender
                    )
                    // 第2层：收件人
                    .withRelation(
                        ShippingInfo::getReceiverId,
                        ids -> contactRepository.findAllById(ids),
                        Contact::getId,
                        ShippingInfoVO::setReceiver
                    )
            )
            .build();
    }
}
```

**3 层嵌套示例：**

```java
/**
 * 3 层嵌套：Order → ShippingInfo → Contact → Address
 *
 * 执行流程：
 * 1. 并行查询第1层：User、ShippingInfo
 * 2. 从 ShippingInfo 提取 ID，查询第2层：Sender、Receiver
 * 3. 从 Receiver 提取 ID，查询第3层：Address
 */
public List<OrderVO> getOrderList3Level() {
    List<Order> orders = orderRepository.findAll();
    if (orders.isEmpty()) return Collections.emptyList();

    return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
        .parallel()
        // 第1层：用户
        .withRelation(
            Order::getUserId,
            ids -> userRepository.findAllById(ids),
            User::getId,
            OrderVO::setUser
        )
        // 第1层 → 第2层 → 第3层
        .withNested(
            Order::getShippingInfoId,
            ids -> shippingInfoRepository.findAllById(ids),
            ShippingInfo::getId,
            shippingInfoMapper::toVO,
            OrderVO::setShippingInfo,
            nested -> nested
                // 第2层：发件人
                .withRelation(
                    ShippingInfo::getSenderId,
                    ids -> contactRepository.findAllById(ids),
                    Contact::getId,
                    ShippingInfoVO::setSender
                )
                // 第2层 → 第3层：收件人 → 地址
                .withNested(
                    ShippingInfo::getReceiverId,
                    ids -> contactRepository.findAllById(ids),
                    Contact::getId,
                    contactMapper::toVO,
                    ShippingInfoVO::setReceiver,
                    nested2 -> nested2
                        // 第3层：地址
                        .withRelation(
                            Contact::getAddressId,
                            ids -> addressRepository.findAllById(ids),
                            Address::getId,
                            ContactVO::setAddress
                        )
                )
        )
        .build();
}
```

---

### 方式二：AssemblyContext + 预加载数据

**适用场景：** 需要极致控制查询逻辑或跨层共享数据的复杂场景

**特点：**
- 灵活性最高：完全控制查询逻辑和数据传递
- 性能最优：所有关联查询可全部并行执行
- 代码稍长：需要手动预加载和映射数据

**实现方式：**

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import io.github.lookfukc.non1.core.AssemblyContext;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ShippingInfoRepository shippingInfoRepository;
    private final ContactRepository contactRepository;
    private final OrderMapper orderMapper;
    private final ShippingInfoMapper shippingInfoMapper;

    public List<OrderVO> getOrderListWithShipping() {
        List<Order> orders = orderRepository.findAll();
        if (orders.isEmpty()) return Collections.emptyList();

        // === 预加载嵌套数据 ===
        Set<Long> shippingInfoIds = orders.stream()
                .map(Order::getShippingInfoId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<ShippingInfo> shippingInfos = shippingInfoRepository.findAllById(shippingInfoIds);

        Set<Long> contactIds = new HashSet<>();
        shippingInfos.forEach(info -> {
            if (info.getSenderId() != null) contactIds.add(info.getSenderId());
            if (info.getReceiverId() != null) contactIds.add(info.getReceiverId());
        });
        Map<Long, Contact> contactMap = contactIds.isEmpty() ? new HashMap<>() :
                contactRepository.findAllById(contactIds).stream()
                        .collect(Collectors.toMap(Contact::getId, c -> c));

        // === 组装 ===
        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .parallel()
            .withSharedData("contactMap", contactMap)
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                OrderVO::setUser
            )
            .withRelation(
                Order::getShippingInfoId,
                ids -> shippingInfos,
                ShippingInfo::getId,
                (info, context) -> {
                    Map<Long, Contact> map = context.getShared("contactMap");
                    ShippingInfoVO vo = shippingInfoMapper.toVO(info);
                    vo.setSender(map.get(info.getSenderId()));
                    vo.setReceiver(map.get(info.getReceiverId()));
                    return vo;
                },
                OrderVO::setShippingInfo
            )
            .build();
    }
}
```

### 两种方式对比

| 对比项 | 方式一（withNested） | 方式二（AssemblyContext） |
|--------|----------------------|--------------------------|
| **代码简洁性** | 高，声明式 | 中等，需手动预加载 |
| **查询性能** | 优秀，同层并行 | 最优，全部并行 |
| **灵活性** | 中等 | 最高，完全控制 |
| **适用场景** | 绝大多数嵌套场景 | 需要极致控制的复杂场景 |
| **推荐程度** | 推荐 | 高级场景 |

**选择建议：**
- 2-3 层嵌套 → 用 `withNested`，代码简洁，性能足够
- 需要跨层共享数据、自定义查询逻辑 → 用 `AssemblyContext`

### withNested 执行流程

```
parallel() 模式下的 withNested 执行流程：

第1层（并行）：User、Product、ShippingInfo  ← 3 个查询同时执行
    ↓ ShippingInfo 结果返回后
第2层（并行）：Sender、Receiver              ← 2 个查询同时执行
    ↓ 如有第3层 withNested
第3层（并行）：Address 等                    ← 第3层查询同时执行
```

- 同层所有查询并行执行
- 跨层查询按依赖顺序自动执行
- 无需手动管理 ID 提取和映射

### AssemblyContext API

```java
public class AssemblyContext {
    // 获取共享数据
    <T> T getShared(String key);
    <T> T getShared(String key, T defaultValue);

    // 设置共享数据
    void setShared(String key, Object value);

    // 获取共享数据 Map
    Map<String, Object> getSharedData();

    // 获取 Executor（用于自定义并行）
    Executor getExecutor();

    // 获取当前组装的源列表
    <S> S getCurrentSourceList();
}
```

### 单个对象转换

支持对单个对象进行转换和组装：

```java
// 转换单个订单
OrderVO vo = RelationAssembler.from(
    order,
    OrderVO.class,
    orderMapper::toVO
);
```

### 批量添加共享数据

```java
Map<String, Object> sharedData = new HashMap<>();
sharedData.put("contactMap", contactMap);
sharedData.put("addressMap", addressMap);

RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
    .withSharedData(sharedData)  // 批量添加
    .withRelation(...)
    .build();
```

## 树形结构构建

`TreeBuilder` 用于将扁平的数据列表转换为树形结构，支持部门树、分类树、菜单树等场景。

### TreeBuilder 介绍

**解决什么问题：** 数据库中通常以扁平结构存储层级数据（每条记录有 `id` 和 `parentId`），但前端展示时需要嵌套的树形结构。手动递归构建树代码重复且容易出错，`TreeBuilder` 提供一行链式调用完成转换。

**核心原理：** 一次遍历构建 `id → VO` 和 `parentId → children` 两个映射，再递归组装父子关系。时间复杂度 O(n)，空间复杂度 O(n)。

**树深度和节点数限制：** 没有人为限制。树深度受 JVM 栈深度限制（默认约数千层），实际业务中常见的组织架构树（10-20 层）完全没有问题。节点数量仅受 JVM 堆内存限制，万级到十万级节点可正常处理，百万级建议配合分页或懒加载。

**性能说明：**
- 单次遍历 + HashMap 映射查找，时间复杂度 O(n)
- VO 转换使用构造器缓存和属性复制器缓存，避免重复反射
- 路径信息使用共享 `ArrayList` 在递归中复用，避免每层创建新列表

**适用场景：**

| 场景 | 示例 |
|------|------|
| 组织架构 | 公司 → 部门 → 小组 → 员工 |
| 权限菜单 | 系统管理 → 用户管理 → 用户列表 |
| 商品分类 | 电子产品 → 手机 → 智能手机 |
| 地区联动 | 广东省 → 深圳市 → 南山区 |
| 知识分类 | 技术 → 编程语言 → Java → 框架 |

### 使用场景

```
部门树结构：
研发部 (id=1, parentId=null)
├── 后端组 (id=11, parentId=1)
│   ├── Java 组 (id=111, parentId=11)
│   └── Go 组 (id=112, parentId=11)
└── 前端组 (id=12, parentId=1)
```

### 基础用法

```java
import io.github.lookfukc.non1.core.TreeBuilder;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final DepartmentMapper departmentMapper;

    /**
     * 获取完整的部门树
     */
    public List<DepartmentVO> getDepartmentTree() {
        List<Department> departments = departmentRepository.findAll();

        return TreeBuilder.from(departments, DepartmentVO.class, departmentMapper::toVO)
            .idExtractor(Department::getId)           // 必需：提取 ID
            .parentIdExtractor(Department::getParentId)  // 必需：提取父 ID
            .childrenSetter(DepartmentVO::setChildren)   // 必需：设置子节点
            .build();
    }
}
```

### 高级用法：排序与过滤

```java
/**
 * 获取部门树（带排序、过滤和层级信息）
 */
public List<DepartmentVO> getDepartmentTreeAdvanced() {
    List<Department> departments = departmentRepository.findAll();

    return TreeBuilder.from(departments, DepartmentVO.class, departmentMapper::toVO)
        .idExtractor(Department::getId)
        .parentIdExtractor(Department::getParentId)
        .childrenSetter(DepartmentVO::setChildren)
        // 排序：按 sort 字段排序
        .nodeComparator(Comparator.comparing(Department::getSort))
        // 过滤：只包含状态为 1 的部门
        .nodeFilter(dept -> dept.getStatus() == 1)
        // 层级深度：0, 1, 2...
        .levelSetter((vo, level) -> vo.setLevel(level))
        // 叶子节点标记
        .leafSetter((vo, isLeaf) -> vo.setIsLeaf(isLeaf))
        // 完整路径：研发部 / 后端组 / Java 组
        .pathSetter((vo, path) -> {
            String pathStr = path.stream()
                .map(DepartmentVO::getName)
                .collect(Collectors.joining(" / "));
            vo.setPath(pathStr);
        })
        .build();
}
```

### 指定根节点

```java
/**
 * 获取指定部门的子树
 */
public DepartmentVO getDepartmentSubTree(Long deptId) {
    List<Department> departments = departmentRepository.findAll();

    return TreeBuilder.from(departments, DepartmentVO.class, departmentMapper::toVO)
        .idExtractor(Department::getId)
        .parentIdExtractor(Department::getParentId)
        .childrenSetter(DepartmentVO::setChildren)
        .rootId(deptId)      // 指定根节点
        .buildSingle();      // 返回单个根节点
}
```

### 树工具类（TreeUtils）

`TreeUtils` 是独立于 `TreeBuilder` 的工具类，用于对**已有的树形结构**进行操作。`TreeBuilder` 负责构建树，`TreeUtils` 负责查询和分析树，两者各司其职。

**为什么需要 TreeUtils：** 构建树之后，经常需要对树进行二次操作，比如导出时需要把树展开为扁平列表、面包屑导航需要获取节点路径、权限校验需要查找特定节点。这些操作如果每次手写递归代码既重复又容易出错，`TreeUtils` 将这些常见操作封装为一行调用。

**TreeUtils 方法一览及适用场景：**

| 方法 | 用途 | 典型场景 |
|------|------|----------|
| `flatten` | 树 → 扁平列表 | Excel 导出、批量处理 |
| `search` | 搜索所有匹配节点 | 关键字搜索、批量筛选 |
| `findFirst` | 查找第一个匹配节点 | 根据 ID 查找、快速定位 |
| `getDepth` | 获取最大深度 | 检测树层级、UI 渲染 |
| `countNodes` | 统计节点总数 | 统计报表、分页计算 |
| `getParentPath` | 根节点到目标的路径 | 面包屑导航、权限链路 |

**使用示例：**

```java
import io.github.lookfukc.non1.core.TreeUtils;

// 构建树（由 TreeBuilder 完成）
List<DepartmentVO> tree = departmentService.getDepartmentTree();

// ========== 常用操作 ==========

// 1. 扁平化：将树展开为列表（用于 Excel 导出、批量写入数据库等）
List<DepartmentVO> flatList = TreeUtils.flatten(tree);
// [研发部, 后端组, Java 组, Go 组, 前端组]

// 2. 搜索：查找所有匹配的节点
List<DepartmentVO> result = TreeUtils.search(tree, dept ->
    dept.getName().contains("组")
);
// [后端组, Java 组, Go 组, 前端组]

// 3. 查找第一个：根据 ID 或条件快速定位某个节点
DepartmentVO javaGroup = TreeUtils.findFirst(tree, dept ->
    dept.getId().equals(112L)
);
// Go 组

// 4. 深度：获取树的最大层级数
int depth = TreeUtils.getDepth(tree);
// 3（研发部 → 后端组 → Java 组）

// 5. 节点总数：统计树中所有节点数量
int total = TreeUtils.countNodes(tree);
// 5

// 6. 父路径：获取从根到目标节点的路径（面包屑导航）
List<DepartmentVO> path = TreeUtils.getParentPath(tree, dept ->
    dept.getName().equals("Java 组")
);
// [研发部, 后端组, Java 组]
// 面包屑展示："研发部 / 后端组 / Java 组"
String breadcrumb = path.stream()
    .map(DepartmentVO::getName)
    .collect(Collectors.joining(" / "));
```

**自定义 children 字段名：**

默认通过反射读取 VO 中名为 `children` 的字段。如果你的 VO 字段名不同，可以通过 `childrenFieldName` 参数指定：

```java
// 场景：菜单 VO 的子节点字段叫 "subMenus" 而非 "children"
@Data
public class MenuVO {
    private Long id;
    private Long parentId;
    private String name;
    private List<MenuVO> subMenus;  // 不是 "children"
}

// 指定自定义字段名
List<MenuVO> flatList = TreeUtils.flatten(menuTree, "subMenus");
MenuVO found = TreeUtils.findFirst(menuTree, menu -> menu.getId() == 100L, "subMenus");
int depth = TreeUtils.getDepth(menuTree, "subMenus");
int count = TreeUtils.countNodes(menuTree, "subMenus");
```

> **说明**：所有 `TreeUtils` 方法都有一个 `childrenFieldName` 参数的重载版本，默认值为 `"children"`。

### RelationAssembler + TreeBuilder 组合使用

实际业务中经常需要先组装关联数据（查经理、查人数等），再构建树形结构。可以串联使用：

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import io.github.lookfukc.non1.core.TreeBuilder;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;

    /**
     * 查询部门树（带关联数据）
     * 1. RelationAssembler：批量查询部门经理，组装到 VO
     * 2. TreeBuilder：将带关联数据的 VO 列表构建为树
     */
    public List<DepartmentVO> getDepartmentTreeWithManager() {
        // 第一步：查询所有部门
        List<Department> departments = departmentRepository.findAll();

        // 第二步：使用 RelationAssembler 组装关联数据（部门经理）
        List<DepartmentVO> voList = RelationAssembler.from(departments, DepartmentVO.class, departmentMapper::toVO)
            .withRelation(
                Department::getManagerId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                userMapper::toVO,
                DepartmentVO::setManager
            )
            .build();

        // 第三步：使用 TreeBuilder 将 VO 列表构建为树
        // 传入 Function.identity() 作为转换器，因为已经是 VO 对象
        return TreeBuilder.from(voList, DepartmentVO.class, Function.identity())
            .idExtractor(DepartmentVO::getId)
            .parentIdExtractor(DepartmentVO::getParentId)
            .childrenSetter(DepartmentVO::setChildren)
            .levelSetter((vo, level) -> vo.setLevel(level))
            .leafSetter((vo, isLeaf) -> vo.setIsLeaf(isLeaf))
            .build();
    }
}
```

### 使用实例：部门树查询

组织架构中的部门树查询完整实现。

**数据结构：**
```
Department (部门树)
├── 研发部 (id=1, parentId=null)
│   ├── 后端组 (id=11, parentId=1)
│   │   ├── Java 组 (id=111, parentId=11)
│   │   └── Go 组 (id=112, parentId=11)
│   └── 前端组 (id=12, parentId=1)
└── 市场部 (id=2, parentId=null)
    ├── 品牌组 (id=21, parentId=2)
    └── 推广组 (id=22, parentId=2)
```

**VO 类：**

```java
@Data
public class DepartmentVO {
    private Long id;
    private String name;
    private Long parentId;
    private Integer sort;
    private Integer status;

    // 树形结构字段
    private List<DepartmentVO> children;

    // 扩展字段
    private Integer level;      // 层级深度：0, 1, 2...
    private Boolean isLeaf;     // 是否为叶子节点
    private String path;        // 完整路径：研发部 / 后端组 / Java 组
}
```

**Service 实现：**

```java
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final DepartmentMapper departmentMapper;

    /**
     * 获取完整的部门树
     */
    public List<DepartmentVO> getDepartmentTree() {
        List<Department> departments = departmentRepository.findAll();

        return TreeBuilder.from(departments, DepartmentVO.class, departmentMapper::toVO)
            .idExtractor(Department::getId)
            .parentIdExtractor(Department::getParentId)
            .childrenSetter(DepartmentVO::setChildren)
            .build();
    }

    /**
     * 获取指定部门的子树
     */
    public DepartmentVO getDepartmentSubTree(Long deptId) {
        List<Department> departments = departmentRepository.findAll();

        return TreeBuilder.from(departments, DepartmentVO.class, departmentMapper::toVO)
            .idExtractor(Department::getId)
            .parentIdExtractor(Department::getParentId)
            .childrenSetter(DepartmentVO::setChildren)
            .rootId(deptId)
            .buildSingle();
    }

    /**
     * 获取部门树（带排序、过滤和层级信息）
     */
    public List<DepartmentVO> getDepartmentTreeAdvanced() {
        List<Department> departments = departmentRepository.findAll();

        return TreeBuilder.from(departments, DepartmentVO.class, departmentMapper::toVO)
            .idExtractor(Department::getId)
            .parentIdExtractor(Department::getParentId)
            .childrenSetter(DepartmentVO::setChildren)
            .nodeComparator(Comparator.comparing(Department::getSort))
            .nodeFilter(dept -> dept.getStatus() == 1)
            .levelSetter((vo, level) -> vo.setLevel(level))
            .leafSetter((vo, isLeaf) -> vo.setIsLeaf(isLeaf))
            .pathSetter((vo, path) -> {
                String pathStr = path.stream()
                    .map(DepartmentVO::getName)
                    .collect(Collectors.joining(" / "));
                vo.setPath(pathStr);
            })
            .build();
    }

    /**
     * 扁平化树形结构（用于导出等场景）
     */
    public List<DepartmentVO> getDepartmentListFlat() {
        List<DepartmentVO> tree = getDepartmentTree();
        return TreeUtils.flatten(tree);
    }

    /**
     * 搜索部门（支持在树中搜索）
     */
    public List<DepartmentVO> searchDepartments(String keyword) {
        List<DepartmentVO> tree = getDepartmentTree();
        return TreeUtils.search(tree, dept ->
            dept.getName().contains(keyword)
        );
    }

    /**
     * 获取树的深度
     */
    public int getDepartmentTreeDepth() {
        List<DepartmentVO> tree = getDepartmentTree();
        return TreeUtils.getDepth(tree);
    }
}
```

## 日志与调试

本库内置了分级日志，可通过调整日志级别查看不同详细程度的组装信息，类似 MyBatis/Hibernate 的 SQL 日志体验。

### 日志级别说明

| 级别 | 输出内容 | 适用场景 |
|------|----------|----------|
| **TRACE** | 完整的组装交互过程：ID 提取详情、查询参数、映射构建、命中情况 | 开发调试、问题排查 |
| **DEBUG** | 组装统计信息：查询耗时、分批详情、阶段耗时 | 开发环境监控 |
| **INFO** | 组装完成摘要：数量、查询耗时、转换耗时、总耗时 | 生产环境基础监控 |

**各级别日志输出示例：**

```
# INFO 级别（默认）
INFO  [RelationAssembler] 组装完成: 数量=100, 查询耗时=15ms, 转换耗时=3ms, 总耗时=18ms

# DEBUG 级别
DEBUG [RelationAssembler] 开始组装: source=100, relations=2, parallel=true, fast=false, batchSize=0, pageSize=0, voType=OrderVO
DEBUG [RelationAssembler] [query] 提取到50个唯一ID, 一次查询
DEBUG [RelationAssembler] [query] 查询完成: 耗时=15ms, 返回48条结果
DEBUG [RelationAssembler] 查询阶段完成: 耗时=15ms, 关联查询数=2
DEBUG [RelationAssembler] VO转换完成: 数量=100, 耗时=3ms
INFO  [RelationAssembler] 组装完成: 数量=100, 查询耗时=15ms, 转换耗时=3ms, 总耗时=18ms

# TRACE 级别（完整交互过程）
TRACE [RelationAssembler] === 开始组装: 100条 Order -> OrderVO ===
TRACE [RelationAssembler] [query] 提取ID详情: 唯一ID数=50, IDs=[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, ...共50个]
DEBUG [RelationAssembler] [query] 提取到50个唯一ID, 一次查询
DEBUG [RelationAssembler] [query] 查询完成: 耗时=15ms, 返回48条结果
TRACE [RelationAssembler] [query] 映射构建完成: 映射条目=48, 查询结果=48, 重复ID丢弃=0
TRACE [RelationAssembler] [关系 1/2] 映射条目=48, 源数据条目=100
TRACE [RelationAssembler] [query] 提取ID详情: 唯一ID数=30, IDs=[100, 200, 300, ...共30个]
DEBUG [RelationAssembler] [query] 提取到30个唯一ID, 一次查询
DEBUG [RelationAssembler] [query] 查询完成: 耗时=8ms, 返回30条结果
TRACE [RelationAssembler] [query] 映射构建完成: 映射条目=30, 查询结果=30, 重复ID丢弃=0
TRACE [RelationAssembler] [关系 2/2] 映射条目=30, 源数据条目=100
DEBUG [RelationAssembler] VO转换完成: 数量=100, 耗时=3ms
INFO  [RelationAssembler] 组装完成: 数量=100, 查询耗时=15ms, 转换耗时=3ms, 总耗时=18ms
TRACE [RelationAssembler] === 组装完成: 100条 OrderVO ===
```

### 开启 TRACE 日志

**方式一：application.yml 配置（推荐）**

```yaml
logging:
  level:
    io.github.lookfukc.non1: TRACE
```

**方式二：logback.xml 配置**

```xml
<logger name="io.github.lookfukc.non1" level="TRACE"/>
```

**方式三：只对特定类开启 TRACE**

```yaml
logging:
  level:
    io.github.lookfukc.non1.core.RelationAssembler: TRACE
```

> **说明**：TRACE 日志均有 `isTraceEnabled()` 守门，生产环境不开 TRACE 时零性能开销。

### TRACE 日志输出示例

**分批查询场景：**

```
TRACE [RelationAssembler] === 开始组装: 10000条 Order -> OrderVO ===
TRACE [RelationAssembler] [query] 提取ID详情: 唯一ID数=8000, IDs=[1, 2, 3, ...共8000个]
DEBUG [RelationAssembler] [query] 提取到8000个唯一ID, 启用分批查询, batchSize=1000
TRACE [RelationAssembler] [query] 批次1/8: 1000个ID, 耗时=5ms, 返回998条
TRACE [RelationAssembler] [query] 批次2/8: 1000个ID, 耗时=4ms, 返回1000条
...
TRACE [RelationAssembler] [query] 分批查询完成: 8批, 总耗时=42ms, 总结果=7950条
TRACE [RelationAssembler] [query] 映射构建完成: 映射条目=7950, 查询结果=7950, 重复ID丢弃=0
TRACE [RelationAssembler] [关系 1/1] 映射条目=7950, 源数据条目=10000
INFO  [RelationAssembler] 组装完成: 数量=10000, 查询耗时=42ms, 转换耗时=15ms, 总耗时=57ms
TRACE [RelationAssembler] === 组装完成: 10000条 OrderVO ===
```

**从 TRACE 日志可以排查的问题：**
- **ID 提取是否正确**：查看提取的 ID 列表是否符合预期
- **查询命中率**：通过 `映射条目` 和 `源数据条目` 的比值判断命中情况
- **重复 ID**：`重复ID丢弃` 可以发现数据中是否有重复关联
- **分批是否合理**：查看每批的耗时和结果数，调整 `queryBatchSize`

## 完整示例

以下是一个完整的电商订单系统示例，涵盖本库的多种使用方式：基础查询、并行查询、全链路优化、单个对象转换。

### 实体类

```java
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderNo;
    private Long userId;
    private Long productId;
    private Long shopId;
    private Integer quantity;
    private Long amount;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String email;
    private String phone;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Long price;
    private String description;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shops")
public class Shop {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String address;
}
```

### VO 类

```java
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OrderVO {
    private Long id;
    private String orderNo;
    private Long userId;
    private Long productId;
    private Long shopId;
    private Integer quantity;
    private Long amount;

    // 关联对象
    private UserVO user;
    private ProductVO product;
    private ShopVO shop;
}

@Data
@NoArgsConstructor
public class UserVO {
    private Long id;
    private String username;
    private String email;
    private String phone;
}

@Data
@NoArgsConstructor
public class ProductVO {
    private Long id;
    private String name;
    private Long price;
    private String description;
}

@Data
@NoArgsConstructor
public class ShopVO {
    private Long id;
    private String name;
    private String address;
}
```

### Repository 接口

```java
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}

public interface UserRepository extends JpaRepository<User, Long> {
}

public interface ProductRepository extends JpaRepository<Product, Long> {
}

public interface ShopRepository extends JpaRepository<Shop, Long> {
}
```

### MapStruct Mapper

```java
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderVO toVO(Order order);
}

@Mapper(componentModel = "spring")
public interface UserMapper {
    UserVO toVO(User user);
}

@Mapper(componentModel = "spring")
public interface ProductMapper {
    ProductVO toVO(Product product);
}

@Mapper(componentModel = "spring")
public interface ShopMapper {
    ShopVO toVO(Shop shop);
}
```

### Service 实现

```java
import io.github.lookfukc.non1.core.RelationAssembler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ShopRepository shopRepository;
    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    private final ProductMapper productMapper;
    private final ShopMapper shopMapper;

    /**
     * 基础用法：串行查询 + 多关联组装
     */
    public List<OrderVO> getOrderList() {
        List<Order> orders = orderRepository.findAll();

        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .withRelation(
                Order::getUserId,
                ids -> userRepository.findAllById(ids),
                User::getId,
                userMapper::toVO,
                OrderVO::setUser
            )
            .withRelation(
                Order::getProductId,
                ids -> productRepository.findAllById(ids),
                Product::getId,
                productMapper::toVO,
                OrderVO::setProduct
            )
            .withRelation(
                Order::getShopId,
                ids -> shopRepository.findAllById(ids),
                Shop::getId,
                shopMapper::toVO,
                OrderVO::setShop
            )
            .build();
    }

    /**
     * 并行查询：多个关联对象同时查询
     * 3 个查询并行执行，总耗时 ≈ 单个查询耗时
     */
    public List<OrderVO> getOrderListParallel() {
        List<Order> orders = orderRepository.findAll();

        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .parallel()
            .withRelation(Order::getUserId,
                ids -> userRepository.findAllById(ids), User::getId,
                userMapper::toVO, OrderVO::setUser)
            .withRelation(Order::getProductId,
                ids -> productRepository.findAllById(ids), Product::getId,
                productMapper::toVO, OrderVO::setProduct)
            .withRelation(Order::getShopId,
                ids -> shopRepository.findAllById(ids), Shop::getId,
                shopMapper::toVO, OrderVO::setShop)
            .build();
    }

    /**
     * 大数据量场景：全链路优化 + 查询分批 + 分页处理
     */
    public List<OrderVO> getOrderListLarge() {
        List<Order> orders = orderRepository.findAll();

        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .fast()                 // 全链路优化：查询并行 + VO 转换并行
            .queryBatchSize(1000)   // 查询分批：每批最多 1000 个 ID
            .pageSize(10000)        // 分页处理：每页 10000 条
            .withRelation(Order::getUserId,
                ids -> userRepository.findAllById(ids), User::getId,
                userMapper::toVO, OrderVO::setUser)
            .withRelation(Order::getProductId,
                ids -> productRepository.findAllById(ids), Product::getId,
                productMapper::toVO, OrderVO::setProduct)
            .withRelation(Order::getShopId,
                ids -> shopRepository.findAllById(ids), Shop::getId,
                shopMapper::toVO, OrderVO::setShop)
            .build();
    }

    /**
     * 流式分页：大数据量导出场景，峰值内存只占一页
     */
    public void exportOrders() {
        List<Order> orders = orderRepository.findAll();

        RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .parallel()
            .withRelation(Order::getUserId,
                ids -> userRepository.findAllById(ids), User::getId,
                userMapper::toVO, OrderVO::setUser)
            .withRelation(Order::getProductId,
                ids -> productRepository.findAllById(ids), Product::getId,
                productMapper::toVO, OrderVO::setProduct)
            .buildPage(pageResult -> {
                // 每页处理完立即回调，峰值内存只占一页
                exportService.exportToExcel(pageResult);
            });
    }

    /**
     * 单个对象转换
     */
    public OrderVO getOrderDetail(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在"));

        return RelationAssembler.from(order, OrderVO.class, orderMapper::toVO)
            .withRelation(Order::getUserId,
                ids -> userRepository.findAllById(ids), User::getId,
                userMapper::toVO, OrderVO::setUser)
            .withRelation(Order::getProductId,
                ids -> productRepository.findAllById(ids), Product::getId,
                productMapper::toVO, OrderVO::setProduct)
            .withRelation(Order::getShopId,
                ids -> shopRepository.findAllById(ids), Shop::getId,
                shopMapper::toVO, OrderVO::setShop);
    }
}
```

### Controller

```java
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public List<OrderVO> getOrderList() {
        return orderService.getOrderList();
    }

    @GetMapping("/parallel")
    public List<OrderVO> getOrderListParallel() {
        return orderService.getOrderListParallel();
    }

    @GetMapping("/large")
    public List<OrderVO> getOrderListLarge() {
        return orderService.getOrderListLarge();
    }

    @GetMapping("/export")
    public void exportOrders() {
        orderService.exportOrders();
    }

    @GetMapping("/{id}")
    public OrderVO getOrderDetail(@PathVariable Long id) {
        return orderService.getOrderDetail(id);
    }
}
```

## 性能基准

### 查询性能对比

| 场景 | N+1 查询 | 批量查询 | 性能提升 |
|------|----------|----------|----------|
| 1000 条数据，3 个关联对象 | 约 30 秒 | 约 30 毫秒 | 1000 倍 |
| 数据库请求数 | 3001 次 | 3 次 | 减少 99.9% |

### 属性复制器性能对比

10,000 次复制操作基准测试：

| 复制器 | 耗时 | 相对速度 | 适用场景 |
|--------|------|----------|----------|
| MapStruct | 5ms | 10x | 追求极致性能 |
| Spring BeanUtils | 25ms | 2x | Spring 项目推荐 |
| Hutool BeanUtil | 30ms | 1.7x | 已使用 Hutool 的项目 |
| JDK 内省 | 35ms | 1.4x | 纯 JDK 项目 |
| DefaultBeanCopier | 50ms | 1x | 零依赖场景 |

## 常见问题

### VO 类必须有无参构造函数吗？

是的。`RelationAssembler` 和 `TreeBuilder` 都通过反射创建 VO 实例，要求 VO 类必须有公开的无参构造函数。

如果你使用了 Lombok 的 `@Data` 或 `@Getter/@Setter`，默认会生成无参构造函数。但如果同时使用了 `@AllArgsConstructor`，Lombok 不会自动生成无参构造函数，需要手动加上 `@NoArgsConstructor`：

```java
@Data
@NoArgsConstructor       // 必须加这个
@AllArgsConstructor      // 如果有这个，就必须显式声明 @NoArgsConstructor
public class OrderVO {
    private Long id;
    private String orderNo;
    // ...
}
```

### 使用 DefaultBeanCopier 时，源实体和 VO 的字段名必须一致吗？

是的。`DefaultBeanCopier` 基于反射按字段名复制，源实体和 VO 中**名称和类型一致**的字段才会被复制。如果字段名不同，可以使用以下方式：

- **推荐**：使用 MapStruct，通过 `@Mapping` 注解映射不同字段名
- 使用其他复制器（SpringBeanUtilsCopier、HutoolBeanCopier），它们基于 getter/setter 的属性名匹配
- 在 `withRelation` 的 converter 中手动设置差异字段

### withRelation 和 withRelationList 该用哪个？

| 方法 | queryFunction 参数类型 | 适用场景 |
|------|----------------------|----------|
| `withRelation` | `Function<Set<I>, List<R>>` | JPA（`findAllById`）、自定义查询 |
| `withRelationList` | `Function<List<I>, List<R>>` | MyBatis Plus（`selectBatchIds`） |

两者功能完全一致，唯一区别是查询函数接收 `Set` 还是 `List`。如果不确定用哪个，看你的查询方法签名就行。

### fast() 模式有什么注意事项？

`fast()` 模式会并行执行 VO 转换，因此要求：

1. **converter 必须线程安全** — MapStruct 生成的代码天然线程安全，可以放心使用
2. **不要在 converter 中操作共享可变状态** — 例如不要在 converter 中修改外部 List

```java
// 安全：MapStruct converter，无状态
.fast()
.withRelation(..., userMapper::toVO, ...)  // 线程安全

// 不安全：在 converter 中修改外部变量
List<UserVO> externalList = new ArrayList<>();
.fast()
.withRelation(..., user -> {
    externalList.add(someValue);  // 并发修改共享集合
    return userMapper.toVO(user);
}, ...)
```

### 源列表为空时会怎样？

`RelationAssembler.from(emptyList, ...).build()` 会直接返回空列表 `[]`，不会执行任何查询，不会报错。`TreeBuilder` 同理。

### 关联 ID 为 null 的记录会怎么处理？

`withRelation` 在提取 ID 时会自动跳过 `null` 值。如果某个源对象的关联 ID 为 `null`，对应的 VO 字段不会被设置（保持 `null`），不会报错。

### 可以在 withRelation 的 queryFunction 中返回 null 吗？

不建议。如果查询结果为空，应该返回空列表 `Collections.emptyList()` 而不是 `null`。返回 `null` 会导致后续映射构建出现 `NullPointerException`。

## API 参考

### RelationAssembler

构建关联组装操作的核心类。

#### 静态方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `from(List<S>, Class<T>)` | sourceList, voClass | 使用默认属性复制器创建构建器 |
| `from(List<S>, Class<T>, Function<S,T>)` | sourceList, voClass, converter | 使用自定义转换函数创建构建器 |
| `from(List<S>, Class<T>, BeanCopier<S,T>)` | sourceList, voClass, copier | 使用自定义属性复制器创建构建器 |
| `from(S, Class<T>, Function<S,T>)` | source, voClass, converter | 单个对象转换（1.2.0+） |
| `fromMaps(List<Map>, Class<T>)` | mapList, voClass | 从 Map 列表创建构建器（1.2.0+） |
| `fromMaps(List<Map>, Class<T>, Function)` | mapList, voClass, converter | 从 Map 列表创建构建器，使用自定义转换函数（1.2.0+） |

### withRelation 参数说明

**无类型转换（4 参数）：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `extractor` | `Function<S, I>` | 从源对象提取关联 ID |
| `queryFunction` | `Function<Set<I>, List<R>>` | 批量查询函数，接收 ID 集合返回关联对象列表 |
| `relationIdGetter` | `Function<R, I>` | 从关联对象获取 ID |
| `voSetter` | `BiConsumer<T, R>` | 将关联对象设置到 VO |

**带类型转换（5 参数）：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `extractor` | `Function<S, I>` | 从源对象提取关联 ID |
| `queryFunction` | `Function<Set<I>, List<R>>` | 批量查询函数，接收 ID 集合返回关联对象列表 |
| `relationIdGetter` | `Function<R, I>` | 从关联对象获取 ID |
| `converter` | `Function<R, V>` | 转换函数，将关联对象类型转换为 VO 字段类型 |
| `voSetter` | `BiConsumer<T, V>` | 将转换后的对象设置到 VO |

**带类型转换和上下文（5 参数，1.2.0+）：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `extractor` | `Function<S, I>` | 从源对象提取关联 ID |
| `queryFunction` | `Function<Set<I>, List<R>>` | 批量查询函数，接收 ID 集合返回关联对象列表 |
| `relationIdGetter` | `Function<R, I>` | 从关联对象获取 ID |
| `converter` | `BiFunction<R, AssemblyContext, V>` | 带 Context 的转换函数，可访问共享数据 |
| `voSetter` | `BiConsumer<T, V>` | 将转换后的对象设置到 VO |

### withRelationList 参数说明

与 `withRelation` 参数一致，区别是 `queryFunction` 接收 `List<I>` 而非 `Set<I>`，适用于批量查询方法接受 `List` 参数的场景（如 MyBatis Plus 的 `selectBatchIds`）。

| 方法重载 | 参数个数 | 说明 |
|----------|----------|------|
| `withRelationList(extractor, queryFunction, relationIdGetter, voSetter)` | 4 | 基于 List 查询，无类型转换 |
| `withRelationList(extractor, queryFunction, relationIdGetter, converter, voSetter)` | 5 | 基于 List 查询，带类型转换 |
| `withRelationList(extractor, queryFunction, relationIdGetter, converter, voSetter)` | 5 | 基于 List 查询，带 Context（1.2.0+） |

### withNested 参数说明

**withNested（6 参数，1.2.0+）：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `extractor` | `Function<S, I>` | 从源对象提取关联 ID |
| `queryFunction` | `Function<Set<I>, List<R>>` | 批量查询函数，接收 ID 集合返回关联对象列表 |
| `relationIdGetter` | `Function<R, I>` | 从关联对象获取 ID |
| `converter` | `Function<R, V>` | 转换函数，将关联对象类型转换为 VO 字段类型 |
| `voSetter` | `BiConsumer<T, V>` | 将转换后的对象设置到 VO |
| `nestedConfigurer` | `Consumer<NestedBuilder<R, V>>` | 嵌套关联配置回调，在回调中继续配置 `withRelation`、`withNested` 等 |

**withNestedList（6 参数，1.2.0+）：**

与 `withNested` 参数一致，区别是 `queryFunction` 接收 `List<I>` 而非 `Set<I>`，适用于批量查询方法接受 `List` 参数的场景（如 MyBatis Plus 的 `selectBatchIds`）。

### NestedBuilder 配置方法

`NestedBuilder` 是嵌套关联构建器，在 `withNested` / `withNestedList` 的回调中使用，支持以下方法：

| 方法 | 参数 | 说明 | 版本 |
|------|------|------|------|
| `withRelation(...)` | 同 RelationAssembler.withRelation | 配置嵌套层的关联（4/5 参数重载） | 1.2.0 |
| `withRelationList(...)` | 同 RelationAssembler.withRelationList | 配置嵌套层的关联（List 版本，4/5 参数重载） | 1.2.0 |
| `withNested(...)` | 同 withNested | 配置更深层嵌套关联（6 参数） | 1.2.0 |
| `withNestedList(...)` | 同 withNestedList | 配置更深层嵌套关联（List 版本，6 参数） | 1.2.0 |

> **说明**：`NestedBuilder` 支持无限层级嵌套，在 `withNested` 回调中可以继续调用 `withNested`，实现任意深度的嵌套关联配置。

### Builder 配置方法

| 方法 | 参数 | 说明 | 版本 |
|------|------|------|------|
| `parallel()` | 无 | 使用默认 ForkJoinPool 启用并行查询 | 1.0.0 |
| `parallel(Executor)` | executor | 使用自定义线程池启用并行查询 | 1.0.0 |
| `fast()` | 无 | 启用全链路优化（查询并行 + VO 转换并行） | 1.2.0 |
| `fast(Executor)` | executor | 使用自定义线程池启用全链路优化 | 1.2.0 |
| `queryBatchSize()` | 无 | 设置查询分批大小为默认值 1000 | 1.2.0 |
| `queryBatchSize(int)` | batchSize | 设置查询分批大小 | 1.2.0 |
| `pageSize()` | 无 | 设置处理分页大小为默认值 10000 | 1.2.0 |
| `pageSize(int)` | pageSize | 设置处理分页大小 | 1.2.0 |
| `withSharedData(String, Object)` | key, value | 添加共享数据 | 1.2.0 |
| `withSharedData(Map<String, Object>)` | data | 批量添加共享数据 | 1.2.0 |
| `build()` | 无 | 执行组装并返回 VO 列表 | 1.0.0 |
| `withNested(...)` | 6 个参数 | 配置嵌套关联（详细参数见 withNested 参数说明） | 1.2.0 |
| `withNestedList(...)` | 6 个参数 | 配置嵌套关联（List 版本，详细参数见 withNested 参数说明） | 1.2.0 |
| `buildPage(Consumer<List<T>>)` | consumer | 流式分页构建，每页回调 | 1.2.0 |

### TreeBuilder

树形结构构建器，将扁平的数据列表转换为树形结构。

#### 静态方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `from(List<S>, Class<T>)` | sourceList, voClass | 使用默认属性复制器创建构建器 |
| `from(List<S>, Class<T>, Function<S,T>)` | sourceList, voClass, converter | 使用自定义转换函数创建构建器 |
| `from(List<S>, Class<T>, BeanCopier<S,T>)` | sourceList, voClass, copier | 使用自定义属性复制器创建构建器 |

### TreeBuilder.Builder 配置方法

| 方法 | 参数 | 必需 | 说明 |
|------|------|------|------|
| `idExtractor(Function<S, I>)` | 提取 ID 的函数 | 必需 | 如 `Department::getId` |
| `parentIdExtractor(Function<S, I>)` | 提取父 ID 的函数 | 必需 | 如 `Department::getParentId` |
| `childrenSetter(BiConsumerType<T, List<T>>)` | 设置子节点的函数 | 必需 | 如 `DepartmentVO::setChildren` |
| `childrenCleaner(Consumer<T>)` | 清空子节点的函数 | 可选 | 避免转换时残留脏数据 |
| `nodeFilter(Predicate<S>)` | 节点过滤条件 | 可选 | 只保留满足条件的节点 |
| `nodeComparator(Comparator<T>)` | 节点排序规则 | 可选 | 对同层级的子节点排序 |
| `rootId(I)` | 指定根节点 ID | 可选 | 构建以该节点为根的子树 |
| `levelSetter(BiConsumerType<T, Integer>)` | 设置层级深度 | 可选 | 根节点为 0，每深入一层加 1 |
| `leafSetter(BiConsumerType<T, Boolean>)` | 设置是否为叶子节点 | 可选 | 没有子节点的节点标记为 true |
| `pathSetter(BiConsumerType2<T, List<T>>)` | 设置节点路径 | 可选 | 接收从根到当前节点的路径列表 |
| `build()` | 无 | - | 返回所有根节点的列表 |
| `buildSingle()` | 无 | - | 返回单个根节点（配合 rootId 使用） |

### TreeUtils 工具方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `flatten(List<T>)` | 树形结构 | 扁平化树形结构（按层级遍历顺序） |
| `flatten(List<T>, String)` | 树形结构, children字段名 | 扁平化树形结构（自定义子节点字段名） |
| `search(List<T>, Predicate<T>)` | 树形结构, 匹配条件 | 搜索树中所有满足条件的节点 |
| `search(List<T>, Predicate<T>, String)` | 树形结构, 匹配条件, 字段名 | 搜索（自定义子节点字段名） |
| `findFirst(List<T>, Predicate<T>)` | 树形结构, 匹配条件 | 查找第一个满足条件的节点（深度优先） |
| `findFirst(List<T>, Predicate<T>, String)` | 树形结构, 匹配条件, 字段名 | 查找第一个（自定义子节点字段名） |
| `getDepth(List<T>)` | 树形结构 | 获取树的最大深度 |
| `getDepth(List<T>, String)` | 树形结构, 字段名 | 获取深度（自定义子节点字段名） |
| `countNodes(List<T>)` | 树形结构 | 统计树节点总数 |
| `countNodes(List<T>, String)` | 树形结构, 字段名 | 统计节点数（自定义子节点字段名） |
| `getParentPath(List<T>, Predicate<T>)` | 树形结构, 目标条件 | 获取从根到目标节点的路径（含目标） |
| `getParentPath(List<T>, Predicate<T>, String)` | 树形结构, 目标条件, 字段名 | 获取路径（自定义子节点字段名） |

> **说明**：所有方法都支持通过 `childrenFieldName` 参数自定义子节点字段名，默认为 `"children"`。如果 VO 中的子节点字段名是 `childNodes`、`subList`、`items` 等，可以指定自定义字段名。

### AssemblyContext

组装上下文，用于在关联组装过程中传递共享数据。

| 方法 | 参数 | 说明 |
|------|------|------|
| `getShared(String)` | key | 获取共享数据，不存在返回 null |
| `getShared(String, T)` | key, defaultValue | 获取共享数据，不存在返回默认值 |
| `setShared(String, Object)` | key, value | 设置共享数据 |
| `getSharedData()` | 无 | 获取共享数据 Map |
| `getExecutor()` | 无 | 获取并行查询执行器 |
| `getCurrentSourceList()` | 无 | 获取当前组装的源列表 |

### BeanCopier

属性复制器接口，支持自定义实现。

```java
@FunctionalInterface
public interface BeanCopier<S, T> {
    T copy(S source, Supplier<T> targetSupplier);
}
```

**内置实现：**

| 实现类 | 获取方式 | 依赖 |
|--------|----------|------|
| `DefaultBeanCopier` | `DefaultBeanCopier.INSTANCE` | 无 |
| `SpringBeanUtilsCopier` | `SpringBeanUtilsCopier.of()` | Spring |
| `HutoolBeanCopier` | `HutoolBeanCopier.of()` | Hutool |
| `JdkBeansCopier` | `JdkBeansCopier.of()` | 无 |
| `MapStructBeanCopier` | `MapStructBeanCopier.of(Function<S,T>)` | 无（包装转换函数） |

## 更新日志

### 1.2.0
- 新增 `withNested` / `withNestedList` 嵌套关联配置方法，声明式配置多层嵌套关联，库内部自动处理 ID 提取、批量查询和对象组装
- 新增 `NestedBuilder` 嵌套关联构建器，支持在嵌套回调中继续配置 `withRelation`、`withRelationList`、`withNested`、`withNestedList`
- 嵌套关联支持并行执行：同层查询自动并行，跨层查询按依赖顺序执行
- 新增 `fast()` / `fast(Executor)` 全链路优化模式，同时开启查询并行和 VO 转换并行，充分利用多核性能
- 新增 `queryBatchSize()` / `queryBatchSize(int)` 查询分批，当关联 ID 数量超过阈值时自动拆分为多次查询，防止 SQL IN 子句过长
- 新增 `pageSize()` / `pageSize(int)` 分页处理，源数据量超过阈值时自动分页构建，降低峰值内存占用
- 新增 `buildPage(Consumer<List<T>>)` 流式分页构建，每处理完一页立即回调，峰值内存只占一页
- 新增 `withSharedData(String, Object)` / `withSharedData(Map<String, Object>)` 共享数据传递，支持多层嵌套组装场景
- 新增 `AssemblyContext` 组装上下文类，提供共享数据存取、执行器获取、当前源列表访问等能力
- 新增带 `AssemblyContext` 的 `withRelation` / `withRelationList` 重载（`BiFunction<R, AssemblyContext, V>` converter），支持在转换函数中访问预加载数据
- 新增 `from(S, Class<T>, Function<S,T>)` 单个对象转换便捷方法
- 新增 TRACE 级别日志，输出完整的组装交互过程：ID 提取详情、查询参数、映射构建、命中情况、分批详情
- 新增 `fromMaps` / `fromMaps(List, Class, Function)` 工厂方法，支持从 `Map<String, Object>` 列表创建构建器，适用于 MongoDB、动态查询等场景
- `TreeUtils` 新增 `findFirst` 方法，查找树中第一个满足条件的节点（深度优先）
- `TreeUtils` 新增 `getParentPath` 方法，获取从根节点到目标节点的路径
- `TreeUtils` 新增 `countNodes` 方法，统计树节点总数
- `TreeUtils` 所有方法支持自定义 `childrenFieldName` 参数，不再硬编码 `children` 字段名
- 性能优化：`buildSequentially` / `buildInParallel` 使用数组替代 ArrayList.get()，减少热路径开销
- 性能优化：`TreeUtils` 递归方法传递 Field 参数，避免每个节点重复查 ConcurrentHashMap 缓存

### 1.1.0
- 兼容 Spring Boot 2.x 和 3.x（双重自动配置支持）

### 1.0.0
- 初始发布
- 核心 `RelationAssembler` 关联组装器
- `TreeBuilder` 树形结构构建器
- 多种属性复制器支持

## 仓库地址

[Maven 仓库](https://repo1.maven.org/maven2/io/github/lookfukc/no-n1-spring-boot-starter/)

## 许可证

[Apache License 2.0](LICENSE)

## 作者

lookfukc
