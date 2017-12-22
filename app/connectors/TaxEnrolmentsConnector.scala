/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package connectors

import audit.Auditable
import config.{BusinessCustomerFrontendAuditConnector, WSHttp}
import metrics.{Metrics, MetricsEnum}
import models._
import play.api.Logger
import play.api.http.Status._
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.http._
import uk.gov.hmrc.play.audit.model.{Audit, EventTypes}
import uk.gov.hmrc.play.config.{AppName, ServicesConfig}
import utils.GovernmentGatewayConstants

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object TaxEnrolmentsConnector extends TaxEnrolmentsConnector {
  val audit: Audit = new Audit(AppName.appName, BusinessCustomerFrontendAuditConnector)
  val appName: String = AppName.appName
  val metrics = Metrics
  val serviceUrl = baseUrl("enrolment-store-proxy")
  val enrolmentUrl = s"$serviceUrl/enrolment-store-proxy/enrolment-store"
  val http: CoreGet with CorePost = WSHttp
}

trait TaxEnrolmentsConnector extends ServicesConfig with RawResponseReads with Auditable {

  def serviceUrl: String

  def enrolmentUrl: String

  def http: CoreGet with CorePost

  def metrics: Metrics

  def enrol(enrolRequest: NewEnrolRequest, groupId: String, arn: String)(implicit hc: HeaderCarrier): Future[HttpResponse] = {

    val enrolmentKey = s"${GovernmentGatewayConstants.KnownFactsAgentServiceName}~${GovernmentGatewayConstants.KnownFactsAgentRefNo}~$arn"
    val postUrl = s"""$enrolmentUrl/groups/$groupId/enrolments/$enrolmentKey"""

    val jsonData = Json.toJson(enrolRequest)

    Logger.error(s"postUrl ->> $postUrl --- Json Data --> ${Json.prettyPrint(Json.toJson(jsonData))}")

    val timerContext = metrics.startTimer(MetricsEnum.EMAC_AGENT_ENROL)
    http.POST[JsValue, HttpResponse](postUrl, jsonData) map { response =>
      timerContext.stop()
      auditEnrolCall(enrolRequest, response)
      processResponse(response, postUrl, enrolRequest)
    }
  }

  private def processResponse(response: HttpResponse, postUrl: String, enrolRequest: NewEnrolRequest) =
    response.status match {
      case OK =>
        metrics.incrementSuccessCounter(MetricsEnum.EMAC_AGENT_ENROL)
        response
      case BAD_REQUEST =>
        metrics.incrementFailedCounter(MetricsEnum.EMAC_AGENT_ENROL)
        Logger.warn(s"[TaxEnrolmentsConnector][enrol] - " +
          s"emacf url:$postUrl, " +
          s"Bad Request Exception account Ref:${enrolRequest.verifiers}, " +
          s"Service: ${GovernmentGatewayConstants.KnownFactsAgentServiceName}")
        throw new BadRequestException(response.body)
      case NOT_FOUND =>
        metrics.incrementFailedCounter(MetricsEnum.EMAC_AGENT_ENROL)
        Logger.warn(s"[TaxEnrolmentsConnector][enrol] - " +
          s"Not Found Exception account Ref:${enrolRequest.verifiers}, " +
          s"Service: ${GovernmentGatewayConstants.KnownFactsAgentServiceName}}")
        throw new NotFoundException(response.body)
      case SERVICE_UNAVAILABLE =>
        metrics.incrementFailedCounter(MetricsEnum.EMAC_AGENT_ENROL)
        Logger.warn(s"[TaxEnrolmentsConnector][enrol] - " +
          s"emac url:$postUrl, " +
          s"Service Unavailable Exception account Ref:${enrolRequest.verifiers}, " +
          s"Service: ${GovernmentGatewayConstants.KnownFactsAgentServiceName}}")
        throw new ServiceUnavailableException(response.body)
      case BAD_GATEWAY =>
        metrics.incrementFailedCounter(MetricsEnum.EMAC_AGENT_ENROL)
        Logger.warn(s"[TaxEnrolmentsConnector][enrol] - BAD_GATEWAY " +
          s"emac url:$postUrl, " +
          s"Service: ${GovernmentGatewayConstants.KnownFactsAgentServiceName}, " +
          s"Enrol Known Facts:${enrolRequest.verifiers}, " +
          s"Reponse Body: ${response.body}," +
          s"Reponse Status: ${response.status}")
        response
      case status =>
        metrics.incrementFailedCounter(MetricsEnum.EMAC_AGENT_ENROL)
        Logger.warn(s"[TaxEnrolmentsConnector][enrol] - " +
          s"emac url:$postUrl, " +
          s"status:$status Exception account Ref:${enrolRequest.verifiers}, " +
          s"Service: ${GovernmentGatewayConstants.KnownFactsAgentServiceName}" +
          s"Reponse Body: ${response.body}," +
          s"Reponse Status: ${response.status}")
        throw new InternalServerException(response.body)
    }

  private def auditEnrolCall(input: NewEnrolRequest, response: HttpResponse)(implicit hc: HeaderCarrier) = {
    val eventType = response.status match {
      case OK => EventTypes.Succeeded
      case _ => EventTypes.Failed
    }
    sendDataEvent(transactionName = "emacEnrolCallES08",
      detail = Map("txName" -> "emacEnrolCall",
        "friendlyName" -> s"${input.friendlyName}",
        "serviceName" -> s"${GovernmentGatewayConstants.KnownFactsAgentServiceName}",
        "knownFacts" -> s"${input.verifiers}",
        "responseStatus" -> s"${response.status}",
        "responseBody" -> s"${response.body}",
        "status" -> s"${eventType}"))
  }

}
