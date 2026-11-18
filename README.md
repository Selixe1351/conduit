# Conduit

Save your data in MongoDB and keep it in sync across all your servers. Java 21+.

## Install

```
./gradlew publishToMavenLocal
```

```groovy
repositories { mavenLocal() }
dependencies { implementation 'com.selixe.conduit:conduit:0.1.0' }
```

## 1. Describe your data

```kotlin
data class Punishment(val type: String, val reason: String)

data class Profile(
    @param:BsonId val uuid: UUID,
    var name: String,
    var coins: Long = 0,
    val punishments: MutableList<Punishment> = mutableListOf()
)
```

## 2. Connect when your plugin starts

```kotlin
val conduit = Conduit("mongodb://localhost:27017", "core", "redis://localhost:6379")
val profiles = conduit.store<Profile>("profiles")
```

## 3. Use it

```kotlin
profiles.insert(Profile(uuid, "Steve"))

val profile = profiles.get(uuid)

profiles.update(uuid) {
    it.coins += 100
    it.punishments.add(Punishment("MUTE", "Spam"))
}

profiles.delete(uuid)
```

Every server sees the change straight away.

## 4. Close it when your plugin stops

```kotlin
conduit.close()
```

## Read-only (recommended)

Give your data a read-only interface so it can only be changed inside `update`:

```kotlin
interface Profile {
    val uuid: UUID
    val coins: Long
    val punishments: List<Punishment>
}

data class ProfileData(
    @param:BsonId override val uuid: UUID,
    override var coins: Long = 0,
    override val punishments: MutableList<Punishment> = mutableListOf()
) : Profile

val profiles = conduit.store<Profile, ProfileData>("profiles")

profiles.get(uuid)!!.coins += 100          // does not compile
profiles.update(uuid) { it.coins += 100 }  // works
```

## More

| | |
|---|---|
| `profiles.all()` | every profile |
| `profiles.findOne(Profile::name, "Steve")` | search by a field |
| `profiles.find(Profile::coins, 100)` | all matches |
| `conduit.publish("kick", Kick(uuid))` | send a message to every server |
| `conduit.subscribe<Kick>("kick") { }` | receive it |
| `async { }` | run something off the main thread |

## Rules

- Mark the id with `@param:BsonId`.
- Only change data inside `update { }`. Changes made anywhere else are not saved.
- Give new fields a default value so old profiles still load.
