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

```kotlin
data class Order(val product: String, val amount: Int)

data class User(
    @param:BsonId val id: UUID,
    var name: String,
    var balance: Long = 0,
    val orders: MutableList<Order> = mutableListOf()
)
```

## 2. Connect on startup

```kotlin
val conduit = Conduit("mongodb://localhost:27017", "shop", "redis://localhost:6379")
val users = conduit.store<User>("users")
```

## 3. Use it

```kotlin
users.insert(User(id, "Alice"))

val user = users.get(id)

users.update(id) {
    it.balance += 100
    it.orders.add(Order("book", 1))
}

users.delete(id)
```

Every instance sees the change on its next read.

## 4. Close on shutdown

```kotlin
conduit.close()
```

## Read-only (recommended)

Give your data a read-only interface so it can only be changed inside `update`:

```kotlin
interface User {
    val id: UUID
    val balance: Long
    val orders: List<Order>
}

data class UserData(
    @param:BsonId override val id: UUID,
    override var balance: Long = 0,
    override val orders: MutableList<Order> = mutableListOf()
) : User

val users = conduit.store<User, UserData>("users")

users.get(id)!!.balance += 100          // does not compile
users.update(id) { it.balance += 100 }  // works
```

## More

| | |
|---|---|
| `users.all()` | every value |
| `users.findOne(User::name, "Alice")` | search by a field |
| `users.find(User::balance, 100)` | all matches |
| `conduit.publish("events", Event(id))` | send a message to every instance |
| `conduit.subscribe<Event>("events") { }` | receive it |
| `async { }` | run something on a background thread |

## Rules

- Mark the id with `@param:BsonId`.
- Only change data inside `update { }`. Changes made anywhere else are not saved.
- Give new fields a default value so existing documents still load.

## License

[MIT](LICENSE)
