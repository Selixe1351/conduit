# Conduit

Store Kotlin objects in MongoDB and keep their cache in sync across every instance of your application through Redis. Java 21+.

## Install

```groovy
repositories {
    maven { url 'https://repo.selixe.com/repository/api/' }
}

dependencies {
    implementation 'com.selixe.conduit:conduit:1.0.0'
}
```

## 1. Describe your data

A read-only interface, and the class that implements it:

```kotlin
interface IUser {
    val id: UUID
    val name: String
    val balance: Long
}

data class User(
    @Identifier override val id: UUID,
    override var name: String,
    override var balance: Long = 0
) : IUser
```

## 2. Connect on startup

```kotlin
val conduit = Conduit("mongodb://localhost:27017", "shop", "redis://localhost:6379")
val users = conduit.store<IUser, User>("users")
```

## 3. Use it

```kotlin
users.insert(User(id, "Alice"))

val user = users.get(id)

users.update(id) { it.balance += 100 }

users.delete(id)
```

`get` returns the read-only `IUser`, so `users.get(id)!!.balance += 100` does not compile. Only `update` lets you change data. Every instance sees the change on its next read.

## 4. Close on shutdown

```kotlin
conduit.close()
```

## Related data

Keep data that grows, like orders, in its own store and point back to the owner:

```kotlin
data class Order(
    @Identifier val id: UUID,
    val userId: UUID,
    val product: String
)

val orders = conduit.store<Order>("orders")

orders.insert(Order(UUID.randomUUID(), userId, "book"))
val userOrders = orders.find(Order::userId, userId)
```

Small data that belongs to one value, like settings, can live inside it as a nested class.

## More

| | |
|---|---|
| `users.all()` | every value |
| `users.findOne(IUser::name, "Alice")` | search by a field |
| `users.find(IUser::balance, 100)` | all matches |
| `conduit.publish("events", Event(id))` | send a message to every instance |
| `conduit.subscribe<Event>("events") { }` | receive it |
| `async { }` | run something on a background thread |

## Rules

- Mark the id with `@Identifier`.
- Give new fields a default value so existing documents still load.

## License

[MIT](LICENSE)
