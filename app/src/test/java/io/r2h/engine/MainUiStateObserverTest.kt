package io.r2h.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainUiStateObserverTest {
    @Test
    fun `destroyed UI observer is no longer updated and replacement sees current state`() = runTest {
        val persisted = mutableListOf<MainUiState>()
        val owner = MainUiStateStore(MainUiState(), persisted::add)
        val oldUiStates = mutableListOf<MainUiState>()
        val newUiStates = mutableListOf<MainUiState>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        val oldUi = backgroundScope.launch(dispatcher) {
            owner.state.collect(oldUiStates::add)
        }
        owner.update { it.copy(currentSection = ProductSection.STUDIO) }
        oldUi.cancel()
        val oldCountAtDestruction = oldUiStates.size

        owner.update { it.copy(currentSection = ProductSection.AGENT) }

        assertEquals(oldCountAtDestruction, oldUiStates.size)
        val newUi = backgroundScope.launch(dispatcher) {
            owner.state.collect(newUiStates::add)
        }
        assertEquals(ProductSection.AGENT, newUiStates.last().currentSection)
        assertEquals(2, persisted.size)
        assertFalse(oldUi.isActive)
        newUi.cancel()
    }
}
