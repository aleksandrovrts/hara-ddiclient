/*
* Copyright © 2017-2021  Kynetics  LLC
*
* This program and the accompanying materials are made
* available under the terms of the Eclipse Public License 2.0
* which is available at https://www.eclipse.org/legal/epl-2.0/
*
* SPDX-License-Identifier: EPL-2.0
*/

package org.eclipse.hara.ddiapiclient.api

import org.eclipse.hara.ddiapiclient.api.model.ArtifactResponse
import org.eclipse.hara.ddiapiclient.api.model.ConfigurationDataRequest
import org.eclipse.hara.ddiapiclient.api.model.CancelActionResponse
import org.eclipse.hara.ddiapiclient.api.model.CancelFeedbackRequest
import org.eclipse.hara.ddiapiclient.api.model.ControllerBaseResponse
import org.eclipse.hara.ddiapiclient.api.model.DeploymentBaseResponse
import org.eclipse.hara.ddiapiclient.api.model.DeploymentFeedbackRequest
import org.eclipse.hara.ddiapiclient.security.Authentication
import org.eclipse.hara.ddiapiclient.security.HawkbitAuthenticationRequestInterceptor
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.HashSet
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class DdiClientDefaultImpl private constructor(private val ddiRestApi: DdiRestApi, private val tenant: String, private val controllerId: String) : DdiClient {

    override suspend fun getSoftwareModulesArtifacts(softwareModuleId: String): List<ArtifactResponse> {
        val artifact = ddiRestApi.getSoftwareModulesArtifacts(tenant, controllerId, softwareModuleId)
        return artifact
    }

    override suspend fun putConfigData(data: ConfigurationDataRequest, onSuccessConfigData: () -> Unit) {
        val responseCode = ddiRestApi.putConfigData(tenant, controllerId, data).code()
        if (responseCode in 200 until 300) {
            onSuccessConfigData.invoke()
        }
    }

    override suspend fun getControllerActions(): ControllerBaseResponse {
        val response = ddiRestApi.getControllerActions(tenant, controllerId)
        return handleResponse(response)
    }

    override suspend fun onControllerActionsChange(etag: String, onChange: OnResourceChange<ControllerBaseResponse>) {
        val response = ddiRestApi.getControllerActions(tenant, controllerId, etag)
        handleOnChangeResponse(response, etag, "BaseResource", onChange)
    }

    override suspend fun getDeploymentActionDetails(actionId: String, historyCount: Int): DeploymentBaseResponse {
        val response = ddiRestApi.getDeploymentActionDetails(tenant, controllerId, actionId, null, historyCount)
        return handleResponse(response)
    }

    override suspend fun onDeploymentActionDetailsChange(actionId: String, historyCount: Int, etag: String, onChange: OnResourceChange<DeploymentBaseResponse>) {
        val response = ddiRestApi.getDeploymentActionDetails(tenant, controllerId, actionId, null, historyCount, etag)
        handleOnChangeResponse(response, etag, "Deployment", onChange)
    }

    override suspend fun getCancelActionDetails(actionId: String): CancelActionResponse {
        val response = ddiRestApi.getCancelActionDetails(tenant, controllerId, actionId)
        return response
    }

    override suspend fun postDeploymentActionFeedback(actionId: String, feedback: DeploymentFeedbackRequest) {
        ddiRestApi.postDeploymentActionFeedback(tenant, controllerId, actionId, feedback)
    }

    override suspend fun postCancelActionFeedback(actionId: String, feedback: CancelFeedbackRequest) {
        ddiRestApi.postCancelActionFeedback(tenant, controllerId, actionId, feedback)
    }

    override suspend fun downloadArtifact(url: String): InputStream {
        return ddiRestApi.downloadArtifact(url).byteStream()
    }

    private suspend fun <T> handleOnChangeResponse(response: Response<T>, etag: String, resourceName: String, onChange: OnResourceChange<T>) {
        when (response.code()) {
            in 200..299 -> {
                val newEtag = response.headers()[ETAG_HEADER] ?: ""
                onChange.invoke(response.body()!!, newEtag)
            }

            HttpURLConnection.HTTP_NOT_MODIFIED -> {}
            else -> throw HttpException(response)
        }
    }

    private fun <T> handleResponse(response: Response<T>): T {
        return when (response.code()) {
            in 200..299 -> response.body()!!
            else -> throw HttpException(response)
        }
    }

    companion object {
        const val ETAG_HEADER = "ETag"

        fun of(haraClientData: ClientData, httpBuilder:OkHttpClient.Builder): DdiClientDefaultImpl {
            val authentications = HashSet<Authentication>()
            with(haraClientData) {
                if (gatewayToken != null) {
                    authentications.add(Authentication.newInstance(Authentication.AuthenticationType.GATEWAY_TOKEN_AUTHENTICATION, gatewayToken!!))
                }
                if (targetToken != null) {
                    authentications.add(Authentication.newInstance(Authentication.AuthenticationType.TARGET_TOKEN_AUTHENTICATION, targetToken!!))
                }
                httpBuilder.interceptors().add(0, HawkbitAuthenticationRequestInterceptor(authentications))
                val ddiRestApi = Retrofit.Builder()
                        .baseUrl(serverUrl)
                        .addConverterFactory(GsonConverterFactory.create())
                        .callbackExecutor(Executors.newSingleThreadExecutor())
                        .client(httpBuilder.build())
                        .build()
                        .create(DdiRestApi::class.java)
                return DdiClientDefaultImpl(ddiRestApi, tenant, controllerId)
            }
        }
    }
}