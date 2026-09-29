@file:JvmName("AsyncTestJvm")

package world.phantasmal.testUtils

import kotlinx.coroutines.runBlocking

actual typealias TestResult = Unit

internal actual fun testAsync(block: suspend () -> Unit): TestResult {
    runBlocking { block() }
}

internal actual fun canExecuteSlowTests(): Boolean = true
