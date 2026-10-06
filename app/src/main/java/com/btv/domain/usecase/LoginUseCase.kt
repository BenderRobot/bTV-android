package com.btv.domain.usecase

import com.btv.data.model.AuthSession
import com.btv.data.repository.AuthRepository

class LoginUseCase(
    private val repository: AuthRepository
) {
    suspend operator fun invoke(serverUrl: String, username: String, password: String): Result<AuthSession> =
        repository.login(serverUrl, username, password)
}
