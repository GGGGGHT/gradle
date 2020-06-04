/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.instantexecution

import spock.lang.Issue
import spock.lang.Unroll

class InstantExecutionBuildOptionsIntegrationTest extends AbstractInstantExecutionIntegrationTest {

    @Unroll
    def "system property from #systemPropertySource used as task and build logic input"() {

        given:
        def instant = newInstantExecutionFixture()
        createDir('root') {
            file('build.gradle.kts') << """

                $greetTask

                val greetingProp = providers.systemProperty("greeting").forUseAtConfigurationTime()
                if (greetingProp.get() == "hello") {
                    tasks.register<Greet>("greet") {
                        greeting.set("hello, hello")
                    }
                } else {
                    tasks.register<Greet>("greet") {
                        greeting.set(greetingProp)
                    }
                }
            """
        }
        def runGreetWith = { String greeting ->
            inDirectory('root')
            switch (systemPropertySource) {
                case SystemPropertySource.COMMAND_LINE:
                    return instantRun('greet', "-Dgreeting=$greeting")
                case SystemPropertySource.GRADLE_PROPERTIES:
                    file('root/gradle.properties').text = "systemProp.greeting=$greeting"
                    return instantRun('greet')
                case SystemPropertySource.GRADLE_PROPERTIES_FROM_MASTER_SETTINGS_DIR:
                    file('master/gradle.properties').text = "systemProp.greeting=$greeting"
                    file('master/settings.gradle').text = """
                        rootProject.projectDir = file('../root')
                    """
                    return instantRun('greet')
            }
            throw new IllegalArgumentException('source')
        }
        when:
        runGreetWith 'hi'

        then:
        output.count("Hi!") == 1
        instant.assertStateStored()

        when:
        runGreetWith 'hi'

        then:
        output.count("Hi!") == 1
        instant.assertStateLoaded()

        when:
        runGreetWith 'hello'

        then:
        output.count("Hello, hello!") == 1
        instant.assertStateStored()

        where:
        systemPropertySource << SystemPropertySource.values()
    }

    enum SystemPropertySource {
        COMMAND_LINE,
        GRADLE_PROPERTIES,
        GRADLE_PROPERTIES_FROM_MASTER_SETTINGS_DIR;

        @Override
        String toString() {
            name().toLowerCase().replace('_', ' ')
        }
    }

    @Unroll
    def "#usage property from properties file used as build logic input"() {

        given:
        def instant = newInstantExecutionFixture()
        buildKotlinFile """

            import org.gradle.api.provider.*

            abstract class PropertyFromPropertiesFile : ValueSource<String, PropertyFromPropertiesFile.Parameters> {

                interface Parameters : ValueSourceParameters {

                    @get:InputFile
                    val propertiesFile: RegularFileProperty

                    @get:Input
                    val propertyName: Property<String>
                }

                override fun obtain(): String? = parameters.run {
                    propertiesFile.get().asFile.takeIf { it.isFile }?.inputStream()?.use {
                        java.util.Properties().apply { load(it) }
                    }?.get(propertyName.get()) as String?
                }
            }

            val isCi: Provider<String> = providers.of(PropertyFromPropertiesFile::class) {
                parameters {
                    propertiesFile.set(layout.projectDirectory.file("local.properties"))
                    propertyName.set("ci")
                }
            }.forUseAtConfigurationTime()

            if ($expression) {
                tasks.register("run") {
                    doLast { println("ON CI") }
                }
            } else {
                tasks.register("run") {
                    doLast { println("NOT CI") }
                }
            }
        """

        when: "running without a file present"
        instantRun "run"

        then:
        output.count("NOT CI") == 1
        instant.assertStateStored()

        when: "running with an empty file"
        file("local.properties") << ""
        instantRun "run"

        then:
        output.count("NOT CI") == 1
        instant.assertStateLoaded()

        when: "running with the property present in the file"
        file("local.properties") << "ci=true"
        instantRun "run"

        then:
        output.count("ON CI") == 1
        instant.assertStateStored()

        when: "running after changing the file without changing the property value"
        file("local.properties") << "\nunrelated.properties=foo"
        instantRun "run"

        then:
        output.count("ON CI") == 1
        instant.assertStateLoaded()

        when: "running after changing the property value"
        file("local.properties").text = "ci=false"
        instantRun "run"

        then:
        output.count("NOT CI") == 1
        instant.assertStateStored()

        where:
        expression                                     | usage
        'isCi.map(String::toBoolean).getOrElse(false)' | 'mapped'
        'isCi.getOrElse("false") != "false"'           | 'raw'
    }

    @Unroll
    def "#kind property used as task and build logic input"() {

        given:
        def instant = newInstantExecutionFixture()
        buildKotlinFile """

            $greetTask

            val greetingProp = providers.${kind}Property("greeting").forUseAtConfigurationTime()
            if (greetingProp.get() == "hello") {
                tasks.register<Greet>("greet") {
                    greeting.set("hello, hello")
                }
            } else {
                tasks.register<Greet>("greet") {
                    greeting.set(greetingProp)
                }
            }
        """
        when:
        instantRun("greet", "-${option}greeting=hi")

        then:
        output.count("Hi!") == 1
        instant.assertStateStored()

        when:
        instantRun("greet", "-${option}greeting=hi")

        then:
        output.count("Hi!") == 1
        instant.assertStateLoaded()

        when:
        instantRun("greet", "-${option}greeting=hello")

        then:
        output.count("Hello, hello!") == 1
        instant.assertStateStored()
        outputContains "$description property 'greeting' has changed"

        where:
        kind     | option | description
        'system' | 'D'    | 'system'
        'gradle' | 'P'    | 'Gradle'
    }

    @Issue("https://github.com/gradle/gradle/issues/13334")
    @Unroll
    def "absent #operator used as optional task input"() {

        given:
        def instant = newInstantExecutionFixture()
        buildKotlinFile """

            val stringProvider = providers
                .$operator("string")

            abstract class PrintString : DefaultTask() {

                @get:Input
                @get:Optional
                abstract val string: Property<String>

                @TaskAction
                fun printString() {
                    println("The string is " + (string.orNull ?: "absent"))
                }
            }

            tasks.register<PrintString>("printString") {
                string.set(stringProvider)
            }
        """

        when:
        instantRun "printString"

        then:
        output.count("The string is absent") == 1
        instant.assertStateStored()

        when:
        instantRun "printString"

        then:
        output.count("The string is absent") == 1
        instant.assertStateLoaded()

        where:
        operator << ['systemProperty', 'gradleProperty', 'environmentVariable']
    }

    @Issue("https://github.com/gradle/gradle/issues/13333")
    @Unroll
    def "absent #operator orElse #orElseKind used as task input"() {

        given:
        def instant = newInstantExecutionFixture()
        buildKotlinFile """

            val stringProvider = providers
                .$operator("string")
                .orElse($orElseArgument)

            abstract class PrintString : DefaultTask() {

                @get:Input
                abstract val string: Property<String>

                @TaskAction
                fun printString() {
                    println("The string is " + string.get())
                }
            }

            tasks.register<PrintString>("printString") {
                string.set(stringProvider)
            }
        """
        def printString = { string ->
            switch (operator) {
                case 'systemProperty':
                    instantRun "printString", "-Dstring=$string"
                    break
                case 'gradleProperty':
                    instantRun "printString", "-Pstring=$string"
                    break
                case 'environmentVariable':
                    withEnvironmentVars(string: string)
                    instantRun "printString"
                    break
            }
        }

        when:
        instantRun "printString"

        then:
        output.count("The string is absent") == 1
        instant.assertStateStored()

        when:
        printString "alice"

        then:
        output.count("The string is alice") == 1
        instant.assertStateLoaded()

        when:
        printString "bob"

        then:
        output.count("The string is bob") == 1
        instant.assertStateLoaded()

        where:
        [operator, orElseKind] << [
            ['systemProperty', 'gradleProperty', 'environmentVariable'],
            ['primitive', 'provider']
        ].combinations()
        orElseArgument = orElseKind == 'primitive'
            ? '"absent"'
            : 'providers.provider { "absent" }'
    }

    def "mapped system property used as task input"() {

        given:
        def instant = newInstantExecutionFixture()
        buildKotlinFile """

            val sysPropProvider = providers
                .systemProperty("thread.pool.size")
                .map(Integer::valueOf)
                .orElse(1)

            abstract class TaskA : DefaultTask() {

                @get:Input
                abstract val threadPoolSize: Property<Int>

                @TaskAction
                fun act() {
                    println("ThreadPoolSize = " + threadPoolSize.get())
                }
            }

            tasks.register<TaskA>("a") {
                threadPoolSize.set(sysPropProvider)
            }
        """

        when:
        instantRun("a")

        then:
        output.count("ThreadPoolSize = 1") == 1
        instant.assertStateStored()

        when:
        instantRun("a", "-Dthread.pool.size=4")

        then:
        output.count("ThreadPoolSize = 4") == 1
        instant.assertStateLoaded()

        when:
        instantRun("a", "-Dthread.pool.size=3")

        then:
        output.count("ThreadPoolSize = 3") == 1
        instant.assertStateLoaded()
    }

    @Unroll
    def "system property #usage used as build logic input"() {

        given:
        def instant = newInstantExecutionFixture()
        buildKotlinFile """
            val isCi = providers.systemProperty("ci").forUseAtConfigurationTime()
            if ($expression) {
                tasks.register("run") {
                    doLast { println("ON CI") }
                }
            } else {
                tasks.register("run") {
                    doLast { println("NOT CI") }
                }
            }
        """

        when:
        instantRun "run"

        then:
        output.count("NOT CI") == 1
        instant.assertStateStored()

        when:
        instantRun "run"

        then:
        output.count("NOT CI") == 1
        instant.assertStateLoaded()

        when:
        instantRun "run", "-Dci=true"

        then:
        output.count("ON CI") == 1
        instant.assertStateStored()

        where:
        expression                                     | usage
        "isCi.map(String::toBoolean).getOrElse(false)" | "value"
        "isCi.isPresent"                               | "presence"
    }

    def "environment variable used as task and build logic input"() {

        given:
        def instant = newInstantExecutionFixture()
        buildKotlinFile """

            $greetTask

            val greetingVar = providers.environmentVariable("GREETING")
            if (greetingVar.forUseAtConfigurationTime().get().startsWith("hello")) {
                tasks.register<Greet>("greet") {
                    greeting.set("hello, hello")
                }
            } else {
                tasks.register<Greet>("greet") {
                    greeting.set(greetingVar)
                }
            }
        """
        when:
        withEnvironmentVars(GREETING: "hi")
        instantRun("greet")

        then:
        output.count("Hi!") == 1
        instant.assertStateStored()

        when:
        withEnvironmentVars(GREETING: "hi")
        instantRun("greet")

        then:
        output.count("Hi!") == 1
        instant.assertStateLoaded()

        when:
        withEnvironmentVars(GREETING: "hello")
        instantRun("greet")

        then:
        output.count("Hello, hello!") == 1
        outputContains "environment variable 'GREETING' has changed"
        instant.assertStateStored()
    }

    @Unroll
    def "file contents #usage used as build logic input"() {

        given:
        def instant = newInstantExecutionFixture()
        buildKotlinFile """
            val ciFile = layout.projectDirectory.file("ci")
            val isCi = providers.fileContents(ciFile)
            if (isCi.$expression) {
                tasks.register("run") {
                    doLast { println("ON CI") }
                }
            } else {
                tasks.register("run") {
                    doLast { println("NOT CI") }
                }
            }
        """

        when:
        instantRun "run"

        then:
        output.count("NOT CI") == 1
        instant.assertStateStored()

        when:
        instantRun "run"

        then:
        output.count("NOT CI") == 1
        instant.assertStateLoaded()

        when:
        file("ci").text = "true"
        instantRun "run"

        then:
        output.count("ON CI") == 1
        instant.assertStateStored()

        when: "file is touched but unchanged"
        file("ci").text = "true"
        instantRun "run"

        then: "cache is still valid"
        output.count("ON CI") == 1
        instant.assertStateLoaded()

        when: "file is changed"
        file("ci").text = "false"
        instantRun "run"

        then: "cache is NO longer valid"
        output.count(usage.endsWith("presence") ? "ON CI" : "NOT CI") == 1
        outputContains "file 'ci' has changed"
        instant.assertStateStored()

        where:
        expression                                                                            | usage
        "asText.forUseAtConfigurationTime().map(String::toBoolean).getOrElse(false)"          | "text"
        "asText.forUseAtConfigurationTime().isPresent"                                        | "text presence"
        "asBytes.forUseAtConfigurationTime().map { String(it).toBoolean() }.getOrElse(false)" | "bytes"
        "asBytes.forUseAtConfigurationTime().isPresent"                                       | "bytes presence"
    }

    def "mapped file contents used as task input"() {

        given:
        def instant = newInstantExecutionFixture()
        buildKotlinFile """

            val threadPoolSizeProvider = providers
                .fileContents(layout.projectDirectory.file("thread.pool.size"))
                .asText
                .map(Integer::valueOf)

            abstract class TaskA : DefaultTask() {

                @get:Input
                abstract val threadPoolSize: Property<Int>

                @TaskAction
                fun act() {
                    println("ThreadPoolSize = " + threadPoolSize.get())
                }
            }

            tasks.register<TaskA>("a") {
                threadPoolSize.set(threadPoolSizeProvider)
            }
        """

        when:
        file("thread.pool.size").text = "4"
        instantRun("a")

        then:
        output.count("ThreadPoolSize = 4") == 1
        instant.assertStateStored()

        when: "the file is changed"
        file("thread.pool.size").text = "3"
        instantRun("a")

        then: "the instant execution cache is NOT invalidated"
        output.count("ThreadPoolSize = 3") == 1
        instant.assertStateLoaded()
    }

    @Unroll
    def "file contents provider used as #usage has no value when underlying file provider has no value"() {
        given:
        def instant = newInstantExecutionFixture()
        buildKotlinFile """

            $greetTask

            val emptyFileProperty = objects.fileProperty()
            val fileContents = providers.fileContents(emptyFileProperty).asText
            val greetingFromFile: $operatorType = fileContents.$operator("hello")
            tasks.register<Greet>("greet") {
                greeting.set(greetingFromFile)
            }
        """

        when:
        instantRun("greet")

        then:
        output.count("Hello!") == 1
        instant.assertStateStored()

        when:
        instantRun("greet")

        then:
        output.count("Hello!") == 1
        instant.assertStateLoaded()

        where:
        operator                                | operatorType       | usage
        "forUseAtConfigurationTime().getOrElse" | "String"           | "build logic input"
        "orElse"                                | "Provider<String>" | "task input"
    }

    def "can define and use custom value source in a Groovy script"() {

        given:
        def instant = newInstantExecutionFixture()
        buildFile.text = """

            import org.gradle.api.provider.*

            abstract class IsSystemPropertySet implements ValueSource<Boolean, Parameters> {
                interface Parameters extends ValueSourceParameters {
                    Property<String> getPropertyName()
                }
                @Override Boolean obtain() {
                    System.getProperties().get(parameters.getPropertyName().get()) != null
                }
            }

            def isCi = providers.of(IsSystemPropertySet) {
                parameters {
                    propertyName = "ci"
                }
            }
            if (isCi.forUseAtConfigurationTime().get()) {
                tasks.register("build") {
                    doLast { println("ON CI") }
                }
            } else {
                tasks.register("build") {
                    doLast { println("NOT CI") }
                }
            }
        """

        when:
        instantRun "build"

        then:
        output.count("NOT CI") == 1
        instant.assertStateStored()

        when:
        instantRun "build"

        then:
        output.count("NOT CI") == 1
        instant.assertStateLoaded()

        when:
        instantRun "build", "-Dci=true"

        then:
        output.count("ON CI") == 1
        output.contains("because a build logic input of type 'IsSystemPropertySet' has changed")
        instant.assertStateStored()
    }

    private static String getGreetTask() {
        """
            abstract class Greet : DefaultTask() {

                @get:Input
                abstract val greeting: Property<String>

                @TaskAction
                fun greet() {
                    println(greeting.get().capitalize() + "!")
                }
            }
        """.stripIndent()
    }

    private void withEnvironmentVars(Map<String, String> environment) {
        executer.withEnvironmentVars(environment)
    }
}
