package com.dronecamera

import org.junit.Assert.*
import org.junit.Test

class DroneStreamViewModelTest {

    @Test
    fun `initial state is IDLE`() {
        val vm = DroneStreamViewModel()
        assertEquals(StreamState.IDLE, vm.streamState.value)
    }

    @Test
    fun `initial frame is null`() {
        val vm = DroneStreamViewModel()
        assertNull(vm.currentFrame.value)
    }

    @Test
    fun `initial error message is null`() {
        val vm = DroneStreamViewModel()
        assertNull(vm.errorMessage.value)
    }

    @Test
    fun `stopStream resets to IDLE`() {
        val vm = DroneStreamViewModel()
        vm.stopStream()
        assertEquals(StreamState.IDLE, vm.streamState.value)
        assertNull(vm.errorMessage.value)
    }

    @Test
    fun `stopStream can be called multiple times`() {
        val vm = DroneStreamViewModel()
        vm.stopStream()
        vm.stopStream()
        assertEquals(StreamState.IDLE, vm.streamState.value)
    }
}
