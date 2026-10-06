package com.nuvio.tv.core.control

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface JustPlayEntryPoint {
    fun justPlayCoordinator(): JustPlayCoordinator
}
