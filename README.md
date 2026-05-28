# no-n1-spring-boot-starter

用于解决 Java 应用中 N+1 查询问题的轻量级 Spring Boot Starter。

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lookfukc/no-n1-spring-boot-starter.svg)](https://central.sonatype.com/search?q=io.github.lookfukc%20no-n1)
[![License](https://img.shields.io/github/license/lookfukc/no-n1-spring-boot-starter)](LICENSE)

## 版本要求

| 依赖 | 最低版本 | 推荐版本 | 说明 |
|------|----------|----------|------|
| **Java** | 17+ | 17 或 21 | 本项目编译和运行需要 Java 17 或更高版本 |
| **Spring Boot** | 3.0+ | 3.2+ | 本项目基于 Spring Boot 3.2.0 构建，不支持 Spring Boot 2.x |
| **Spring Framework** | 6.0+ | 6.1+ | Spring Boot 3.x 基于 Spring Framework 6.x |

> **注意**：Spring Boot 3.x 使用 `jakarta.*` 命名空间（Jakarta EE），而不是 `javax.*`（Java EE）。如果你的项目还在使用 Spring Boot 2.x，需要先升级到 Spring Boot 3.x 才能使用本库。

## 项目简介

N+1 查询是数据库访问中常见的性能问题，即执行了多次数据库查询而不是单次批量查询。本库提供了一种优雅的解决方案，通过批量查询关联对象并将其组装到 VO 中，将 O(n) 的查询复杂度降低到 O(1)，显著提升性能。

## 核心特性

- **零依赖**：核心功能基于 Java 原生反射实现，无需额外依赖
- **灵活适配**：支持多种属性复制策略（MapStruct、Spring BeanUtils、Hutool、JDK 原生、自定义实现）
- **高性能**：支持多个关联对象的并行查询，进一步提升性能
- **类型安全**：基于泛型设计，编译期进行类型检查
- **易于集成**：开箱即用的 Spring Boot Starter，自动配置

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>io.github.lookfukc</groupId>
    <artifactId>no-n1-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 基础用法

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

## 属性复制器选择

本库提供了多种属性复制实现，请根据项目实际情况选择：

| 复制器 | 依赖 | 性能 | 适用场景 |
|--------|------|------|----------|
| MapStruct | mapstruct | 最高 | 编译期生成代码，性能接近手写 |
| SpringBeanUtilsCopier | Spring | 中等 | 使用 Spring 框架的 BeanUtils，适合 Spring 项目 |
| HutoolBeanCopier | hutool | 中等 | 使用 Hutool 的 BeanUtil，适合已使用 Hutool 的项目 |
| JdkBeansCopier | 无 | 中等 | 使用 JDK 内省机制，纯 Java 方案 |
| DefaultBeanCopier | 无 | 中等 | 内置反射实现，带字段缓存 |

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

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProductMapper productMapper;

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

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProductMapper productMapper;

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

## 完整示例

### 实体类

```java
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderNo;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private Long amount;
}

@Data
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
```

### VO 类

```java
import lombok.Data;

@Data
public class OrderVO {
    private Long id;
    private String orderNo;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private Long amount;
    private UserVO user;
    private ProductVO product;
}

@Data
public class UserVO {
    private Long id;
    private String username;
    private String email;
    private String phone;
}

@Data
public class ProductVO {
    private Long id;
    private String name;
    private Long price;
    private String description;
}
```

### Repository 接口

```java
import org.springframework.data.jpa.repository.JpaRepository;
import io.github.lookfukc.demo.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {
}

public interface UserRepository extends JpaRepository<User, Long> {
}

public interface ProductRepository extends JpaRepository<Product, Long> {
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
    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    private final ProductMapper productMapper;

    /**
     * 获取订单列表，包含用户和商品详细信息
     * 使用批量查询避免 N+1 问题
     */
    public List<OrderVO> getOrderListWithDetails() {
        // 1. 查询所有订单
        List<Order> orders = orderRepository.findAll();

        // 2. 使用 RelationAssembler 批量查询关联对象并组装
        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            // 3. 批量查询并组装用户信息
            .withRelation(
                Order::getUserId,                           // 从订单提取用户 ID
                ids -> userRepository.findAllById(ids),     // 批量查询用户
                User::getId,                                // 从用户获取 ID
                userMapper::toVO,                           // 转换为 UserVO
                OrderVO::setUser                            // 设置到 OrderVO
            )
            // 4. 批量查询并组装商品信息
            .withRelation(
                Order::getProductId,                        // 从订单提取商品 ID
                ids -> productRepository.findAllById(ids),  // 批量查询商品
                Product::getId,                             // 从商品获取 ID
                productMapper::toVO,                        // 转换为 ProductVO
                OrderVO::setProduct                         // 设置到 OrderVO
            )
            .build();
    }

    /**
     * 获取订单列表（并行查询版本）
     * 当有多个关联对象时，并行查询可以进一步提升性能
     */
    public List<OrderVO> getOrderListWithDetailsParallel() {
        List<Order> orders = orderRepository.findAll();

        // 启用并行查询，用户和商品查询会同时执行
        return RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
            .parallel()                                     // 启用并行
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
        return orderService.getOrderListWithDetails();
    }

    @GetMapping("/parallel")
    public List<OrderVO> getOrderListParallel() {
        return orderService.getOrderListWithDetailsParallel();
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

## API 参考

### RelationAssembler

构建关联组装操作的核心类。

#### 静态方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `from(List<S>, Class<T>)` | sourceList, voClass | 使用默认属性复制器创建构建器 |
| `from(List<S>, Class<T>, Function<S,T>)` | sourceList, voClass, converter | 使用自定义转换函数创建构建器 |
| `from(List<S>, Class<T>, BeanCopier<S,T>)` | sourceList, voClass, copier | 使用自定义属性复制器创建构建器 |

#### Builder 方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `parallel()` | 无 | 使用默认线程池启用并行查询 |
| `parallel(Executor)` | executor | 使用自定义线程池启用并行查询 |
| `withRelation(...)` | 4 个参数 | 添加关联配置（无类型转换） |
| `withRelation(...)` | 5 个参数 | 添加关联配置（带类型转换） |
| `withRelationList(...)` | 4 个参数 | 添加基于 List 的关联配置 |
| `withRelationList(...)` | 5 个参数 | 添加基于 List 的关联配置（带转换） |
| `build()` | 无 | 执行组装并返回 VO 列表 |

### withRelation 参数说明

| 参数 | 类型 | 说明 |
|------|------|------|
| `extractor` | `Function<S, I>` | 从源对象提取关联 ID |
| `queryFunction` | `Function<Set<I>, List<R>>` | 批量查询函数，接收 ID 集合返回关联对象列表 |
| `relationIdGetter` | `Function<R, I>` | 从关联对象获取 ID |
| `voSetter` | `BiConsumer<T, R>` | 将关联对象设置到 VO |
| `converter` | `Function<R, V>` | 可选，在设置前转换关联对象类型 |


## 许可证

[Apache License 2.0](LICENSE)

## 作者

lookfukc
