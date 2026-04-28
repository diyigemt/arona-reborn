import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import javax.inject.Inject

open class AronaPluginExtension @Inject constructor(objects: ObjectFactory) {
  val idProperty: Property<String> = objects.property(String::class.java)
  val nameProperty: Property<String> = objects.property(String::class.java)
  val authorProperty: Property<String> = objects.property(String::class.java)
  val versionProperty: Property<String> = objects.property(String::class.java)
  val descriptionProperty: Property<String> = objects.property(String::class.java)
  val mainClassProperty: Property<String> = objects.property(String::class.java)
  val generatedPackageProperty: Property<String> = objects.property(String::class.java)

  var id: String
    get() = idProperty.get()
    set(value) = idProperty.set(value)

  var name: String
    get() = nameProperty.get()
    set(value) = nameProperty.set(value)

  var author: String
    get() = authorProperty.get()
    set(value) = authorProperty.set(value)

  var version: String
    get() = versionProperty.get()
    set(value) = versionProperty.set(value)

  var description: String
    get() = descriptionProperty.get()
    set(value) = descriptionProperty.set(value)

  var mainClass: String
    get() = mainClassProperty.get()
    set(value) = mainClassProperty.set(value)

  var generatedPackage: String
    get() = generatedPackageProperty.get()
    set(value) = generatedPackageProperty.set(value)

  fun resolvedGeneratedPackage(): Provider<String> = generatedPackageProperty.orElse(
    mainClassProperty.map { fqcn ->
      val lastDot = fqcn.lastIndexOf('.')
      require(lastDot > 0) {
        "arona.mainClass must be a fully-qualified class name (got: '$fqcn'); " +
          "or set arona.generatedPackage explicitly."
      }
      fqcn.substring(0, lastDot)
    }
  )
}
