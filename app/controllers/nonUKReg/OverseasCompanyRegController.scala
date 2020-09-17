/*
 * Copyright 2020 HM Revenue & Customs
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

package controllers.nonUKReg

import config.ApplicationConfig
import connectors.{BackLinkCacheConnector, BusinessRegCacheConnector}
import controllers.auth.AuthActions
import controllers.{BackLinkController, ReviewDetailsController}
import forms.BusinessRegistrationForms
import forms.BusinessRegistrationForms._
import javax.inject.Inject
import models.{BusinessRegistration, OverseasCompany}
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import services.BusinessRegistrationService
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import utils.BusinessCustomerConstants.{BusinessRegDetailsId, OverseasRegDetailsId}
import utils.OverseasCompanyUtils

import scala.concurrent.{ExecutionContext, Future}

class OverseasCompanyRegController @Inject()(val authConnector: AuthConnector,
                                             val backLinkCacheConnector: BackLinkCacheConnector,
                                             config: ApplicationConfig,
                                             template: views.html.nonUkReg.overseas_company_registration,
                                             businessRegistrationService: BusinessRegistrationService,
                                             businessRegistrationCache: BusinessRegCacheConnector,
                                             reviewDetailsController: ReviewDetailsController,
                                             mcc: MessagesControllerComponents)
  extends FrontendController(mcc) with AuthActions with BackLinkController with OverseasCompanyUtils {

  implicit val appConfig: ApplicationConfig = config
  implicit val executionContext: ExecutionContext = mcc.executionContext
  val controllerId: String = "OverseasCompanyRegController"


  def view(service: String, addClient: Boolean, redirectUrl: Option[String] = None): Action[AnyContent] = Action.async { implicit request =>
    authorisedFor(service){ implicit authContext =>
      redirectUrl match {
        case Some(x) if !appConfig.isRelative(x) => Future.successful(BadRequest("The redirect url is not correctly formatted"))
        case _ => for {
          backLink <- currentBackLink
          overseasNumber <- businessRegistrationCache.fetchAndGetCachedDetails[OverseasCompany](OverseasRegDetailsId)
        } yield {
          overseasNumber match {
            case Some(oversea) =>
              Ok(template(overseasCompanyForm.fill(oversea), service,
                displayDetails(authContext.isAgent, addClient, service), appConfig.getIsoCodeTupleList, redirectUrl, backLink, request.host + request.uri))
            case None => Ok(template(overseasCompanyForm, service,
              displayDetails(authContext.isAgent, addClient, service), appConfig.getIsoCodeTupleList, redirectUrl, backLink, request.host + request.uri))
          }
        }
      }
    }
  }

  def register(service: String, addClient: Boolean, redirectUrl: Option[String] = None): Action[AnyContent] = Action.async { implicit request =>
    authorisedFor(service){ implicit authContext =>
      redirectUrl match {
        case Some(x) if !appConfig.isRelative(x) => Future.successful(BadRequest("The redirect url is not correctly formatted"))
        case _ => BusinessRegistrationForms.validateNonUK(overseasCompanyForm.bindFromRequest).fold(
          formWithErrors => {
            currentBackLink.map(backLink => BadRequest(template(formWithErrors, service,
              displayDetails(authContext.isAgent, addClient, service), appConfig.getIsoCodeTupleList, redirectUrl, backLink, request.host + request.uri))
            )
          },
          overseasCompany => for {
            cachedBusinessReg <- businessRegistrationCache.fetchAndGetCachedDetails[BusinessRegistration](BusinessRegDetailsId)
            _ <- businessRegistrationCache.cacheDetails[OverseasCompany](OverseasRegDetailsId, overseasCompany)
            _ <- cachedBusinessReg match {
                case Some(businessReg) =>
                  businessRegistrationService.registerBusiness(
                    businessReg,
                    overseasCompany,
                    isGroup = false,
                    isNonUKClientRegisteredByAgent = addClient,
                    service,
                    isBusinessDetailsEditable = true
                  )
                case None => throw new RuntimeException(s"[OverseasCompanyRegController][send] - service :$service. Error : No Cached BusinessRegistration")
              }
            redirectPage <- redirectUrl match {
              case Some(x) => redirectToExternal(x, Some(controllers.nonUKReg.routes.OverseasCompanyRegController.view(service, addClient, Some(x)).url))
              case None => redirectWithBackLink(
                reviewDetailsController.controllerId,
                controllers.routes.ReviewDetailsController.businessDetails(service),
                Some(controllers.nonUKReg.routes.OverseasCompanyRegController.view(service, addClient, redirectUrl).url)
              )
            }
          } yield redirectPage
        )
      }
    }
  }

}
