package io.r2h.engine.core

import io.r2h.engine.api.IR2hEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineServiceAuthorizationTest {

    private val serviceScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        serviceScopes.forEach(CoroutineScope::cancel)
    }

    @Test
    fun `getApiVersion validates the captured Binder calling uid`() {
        val validatedUids = mutableListOf<Int>()
        val service = service(
            callerValidator = CallerValidatorPort { uid ->
                validatedUids += uid
                CallerValidationResult.Allowed("io.r2h.client")
            },
            callingUidProvider = { 4242 },
        )

        assertEquals(IR2hEngineService.API_VERSION, service.apiVersion)
        assertEquals(listOf(4242), validatedUids)
    }

    @Test
    fun `cancel validates the captured Binder calling uid even for an unknown request`() {
        val validatedUids = mutableListOf<Int>()
        val service = service(
            callerValidator = CallerValidatorPort { uid ->
                validatedUids += uid
                CallerValidationResult.Allowed("io.r2h.client")
            },
            callingUidProvider = { 4343 },
        )

        service.cancel("unknown-request")

        assertEquals(listOf(4343), validatedUids)
    }

    @Test
    fun `cancel rejects an untrusted caller before ownership lookup`() {
        val service = service(
            callerValidator = CallerValidatorPort {
                CallerValidationResult.Denied("SIGNING_IDENTITY_NOT_TRUSTED")
            },
            callingUidProvider = { 4444 },
        )

        assertThrows(SecurityException::class.java) {
            service.cancel("request-owned-by-someone-else")
        }
    }

    @Test
    fun `missing validator configuration fails closed`() {
        val scope = newScope()
        val service = EngineServiceImpl(
            scope = scope,
            runtimeRegistry = DefaultRuntimeRegistry(),
            taskRouter = DefaultTaskRouter(),
            callerRateLimiter = CallerRateLimiterPort { _, _ -> CallerRateLimitResult.Allowed },
            callingUidProvider = { 4545 },
        )

        assertThrows(SecurityException::class.java) {
            service.apiVersion
        }
    }

    private fun service(
        callerValidator: CallerValidatorPort,
        callingUidProvider: () -> Int,
    ): EngineServiceImpl = EngineServiceImpl(
        scope = newScope(),
        runtimeRegistry = DefaultRuntimeRegistry(),
        taskRouter = DefaultTaskRouter(),
        callerRateLimiter = CallerRateLimiterPort { _, _ -> CallerRateLimitResult.Allowed },
        callerValidator = callerValidator,
        enginePackageName = "io.r2h.engine",
        callingUidProvider = callingUidProvider,
    )

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        .also(serviceScopes::add)
}
