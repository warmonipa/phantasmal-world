package world.phantasmal.testUtils

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/** The global JS `Promise` without type parameters, which an actual type alias cannot carry. */
@JsName("Promise")
external class AsyncTestPromise

actual typealias TestResult = AsyncTestPromise

// Mocha waits for the returned promise and reports its rejection as the test failure.
@OptIn(DelicateCoroutinesApi::class)
internal actual fun testAsync(block: suspend () -> Unit): TestResult =
    GlobalScope.promise { block() }.unsafeCast<TestResult>()

// KJS is relatively slow, so we don't execute the slow tests on KJS.
internal actual fun canExecuteSlowTests(): Boolean = false
