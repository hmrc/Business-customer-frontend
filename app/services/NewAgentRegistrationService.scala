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

package services

import audit.Auditable
import config.{AuthClientConnector, BusinessCustomerFrontendAuditConnector}
import connectors.{DataCacheConnector, NewBusinessCustomerConnector, TaxEnrolmentsConnector}
import models._
import play.api.Play.current
import play.api.http.Status._
import play.api.i18n.Messages
import play.api.i18n.Messages.Implicits._
import play.api.{Logger, Play}
import uk.gov.hmrc.auth.core.AuthProvider.GovernmentGateway
import uk.gov.hmrc.auth.core.retrieve.{Credentials, ~}
import uk.gov.hmrc.auth.core.retrieve.Retrievals._
import uk.gov.hmrc.auth.core.{AuthConnector, _}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.audit.model.{Audit, EventTypes}
import uk.gov.hmrc.play.config.{AppName, RunMode}
import utils.{BCUtils, GovernmentGatewayConstants}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait NewAgentRegistrationService extends RunMode with Auditable with AuthorisedFunctions {

  def taxEnrolmentsConnector: TaxEnrolmentsConnector

  def dataCacheConnector: DataCacheConnector

  def businessCustomerConnector: NewBusinessCustomerConnector

  def enrolAgent(serviceName: String)(implicit bcContext: BusinessCustomerContext, hc: HeaderCarrier): Future[HttpResponse] = {
    dataCacheConnector.fetchAndGetBusinessDetailsForSession flatMap {
      case Some(businessDetails) => enrolAgent(serviceName, businessDetails)
      case _ =>
        Logger.warn(s"[AgentRegistrationService][enrolAgent] - No Service details found in DataCache for")
        throw new RuntimeException(Messages("bc.business-review.error.not-found"))
    }
  }

  def isAgentEnrolmentAllowed(serviceName: String) :Boolean = {
    getServiceAgentEnrolmentType(serviceName).isDefined
  }

  private def enrolAgent(serviceName: String, businessDetails: ReviewDetails)
                        (implicit bcContext: BusinessCustomerContext, hc: HeaderCarrier): Future[HttpResponse] = {

    val knownFacts = createEnrolmentVerifiers(businessDetails)
    for {
      (groupId, ggCredId) <- getUserAuthDetails
      _ <- businessCustomerConnector.addKnownFacts(knownFacts, getArn(businessDetails))
      enrolResponse <- taxEnrolmentsConnector.enrol(createEnrolRequest(serviceName, knownFacts, ggCredId), groupId, getArn(businessDetails))
    } yield {
      auditEnrolAgent(businessDetails, enrolResponse, createEnrolRequest(serviceName, knownFacts, ggCredId))
      enrolResponse
    }
  }

  private def getServiceAgentEnrolmentType(serviceName: String) : Option[String] = {
    Play.configuration.getString(s"microservice.services.${serviceName.toLowerCase}.agentEnrolmentService")
  }

  private def createEnrolRequest(serviceName: String, knownFacts: Verifiers, ggCredId: String)(implicit bcContext: BusinessCustomerContext): NewEnrolRequest = {
    getServiceAgentEnrolmentType(serviceName) match {
      case Some(enrolServiceName) =>
        NewEnrolRequest(userId = bcContext.user.authContext.user.userId.replace("/auth/session/", ""),
          friendlyName = GovernmentGatewayConstants.FriendlyName,
          `type` = GovernmentGatewayConstants.enrolmentType,
          verifiers = knownFacts.verifiers)
      case _ =>
        Logger.warn(s"[AgentRegistrationService][createEnrolRequest] - No Agent Enrolment name found in config found = $serviceName")
        throw new RuntimeException(Messages("bc.agent-service.error.no-agent-enrolment-service-name", serviceName, serviceName.toLowerCase))
    }
  }

  private def createEnrolmentVerifiers(businessDetails: ReviewDetails)(implicit bcContext: BusinessCustomerContext): Verifiers = {
    val agentRefNo = getArn(businessDetails)
    val knownFacts = List(Verifier(GovernmentGatewayConstants.KnownFactsSafeId, businessDetails.safeId))
    Verifiers(knownFacts)
  }

  private def getUserAuthDetails(implicit hc: HeaderCarrier): Future[(String, String)] = {
    authorised(AuthProviders(GovernmentGateway) and AffinityGroup.Agent).retrieve(credentials and groupIdentifier) {
      case Credentials(ggCredId, _) ~ Some(groupId) => Future.successful(BCUtils.formatGroupId(groupId), ggCredId)
      case _ => throw new RuntimeException("No details found for the agent!")
    }
  }

  private def auditEnrolAgent(businessDetails: ReviewDetails, enrolResponse: HttpResponse, enrolReq: NewEnrolRequest)(implicit hc: HeaderCarrier) = {
    val status = enrolResponse.status match {
      case OK => EventTypes.Succeeded
      case _ => EventTypes.Failed
    }
      sendDataEvent("enrolAgent", detail = Map(
      "txName" -> "enrolAgent",
      "agentReferenceNumber" -> businessDetails.agentReferenceNumber.getOrElse(""),
      "safeId" -> businessDetails.safeId,
      "service" -> GovernmentGatewayConstants.KnownFactsAgentServiceName,
      "status" -> status
    ))
  }

  private def getArn(businessDetails: ReviewDetails): String = {
    businessDetails.agentReferenceNumber.getOrElse {
      throw new RuntimeException(Messages("bc.agent-service.error.no-agent-reference", "[AgentRegistrationService][createEnrolmentVerifiers]"))
    }
  }
}

object NewAgentRegistrationService extends NewAgentRegistrationService {
  val taxEnrolmentsConnector = TaxEnrolmentsConnector
  val dataCacheConnector = DataCacheConnector
  val businessCustomerConnector = NewBusinessCustomerConnector
  val audit: Audit = new Audit(AppName.appName, BusinessCustomerFrontendAuditConnector)
  val appName: String = AppName.appName
  val authConnector: AuthConnector = AuthClientConnector
}