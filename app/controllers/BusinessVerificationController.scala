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

package controllers

import config.ApplicationConfig
import connectors.BackLinkCacheConnector
import controllers.auth.AuthActions
import controllers.nonUKReg.{BusinessRegController, NRLQuestionController}
import forms.BusinessVerificationForms._
import forms._
import javax.inject.{Inject, Singleton}
import models._
import play.api.data.Form
import play.api.i18n.I18nSupport
import play.api.mvc._
import services.BusinessMatchingService
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import utils.BusinessCustomerConstants._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BusinessVerificationController @Inject()(val config: ApplicationConfig,
                                               val authConnector: AuthConnector,
                                               template: views.html.business_verification,
                                               templateSOP: views.html.business_lookup_SOP,
                                               templateLTD: views.html.business_lookup_LTD,
                                               templateUIB: views.html.business_lookup_UIB,
                                               templateOBP: views.html.business_lookup_OBP,
                                               templateLLP: views.html.business_lookup_LLP,
                                               templateLP: views.html.business_lookup_LP,
                                               templateNRL: views.html.business_lookup_NRL,
                                               templateDetailsNotFound: views.html.details_not_found,
                                               val backLinkCacheConnector: BackLinkCacheConnector,
                                               val businessMatchingService: BusinessMatchingService,
                                               val businessRegUKController: BusinessRegUKController,
                                               val businessRegController: BusinessRegController,
                                               val nrlQuestionController: NRLQuestionController,
                                               val reviewDetailsController: ReviewDetailsController,
                                               val homeController: HomeController,
                                               val mcc: MessagesControllerComponents)
  extends FrontendController(mcc) with AuthActions with BackLinkController with I18nSupport {

  implicit val appConfig: ApplicationConfig = config
  implicit val executionContext: ExecutionContext = mcc.executionContext
  val controllerId: String = "BusinessVerificationController"

  def businessVerification(service: String): Action[AnyContent] = Action.async { implicit request =>
    authorisedFor(service) { implicit authContext =>
      currentBackLink map ( backLink =>
        Ok(template(businessTypeForm, authContext.isAgent, service, authContext.isSa, authContext.isOrg, backLink, request.host + request.uri))
      )
    }
  }

  def continue(service: String): Action[AnyContent] = Action.async { implicit request =>
    authorisedFor(service) { implicit authContext =>
      BusinessVerificationForms.validateBusinessType(businessTypeForm.bindFromRequest, service).fold(
        formWithErrors =>
          currentBackLink map ( backLink =>
            BadRequest(template(formWithErrors, authContext.isAgent, service, authContext.isSa, authContext.isOrg, backLink, request.host + request.uri)
          )
        ),
        value => {
          val returnCall = Some(routes.BusinessVerificationController.businessVerification(service).url)
          value.businessType match {
            case Some("NUK") if service.equals("capital-gains-tax") =>
              redirectWithBackLink(businessRegController.controllerId, controllers.nonUKReg.routes.BusinessRegController.register(service, "NUK"), returnCall)
            case Some("NUK") if service.equals("ATED") && !authContext.isAgent =>
              redirectToExternal(appConfig.conf.getConfString(s"ated.overseasSameAccountUrl", throw new Exception("")), Some(controllers.routes.BusinessVerificationController.businessVerification(service).url))
            case Some("NUK") =>
              redirectWithBackLink(nrlQuestionController.controllerId, controllers.nonUKReg.routes.NRLQuestionController.view(service), returnCall)
            case Some("NEW") =>
              redirectWithBackLink(businessRegUKController.controllerId, controllers.routes.BusinessRegUKController.register(service, "NEW"), returnCall)
            case Some("GROUP") =>
              redirectWithBackLink(businessRegUKController.controllerId, controllers.routes.BusinessRegUKController.register(service, "GROUP"), returnCall)
            case Some(busType @ ("SOP" | "UIB" | "LTD" | "OBP" | "LLP" | "LP" | "UT" | "ULTD" | "NRL")) =>
              Future.successful(Redirect(controllers.routes.BusinessVerificationController.businessForm(service, busType)))
            case _ =>
              redirectWithBackLink(homeController.controllerId, controllers.routes.HomeController.homePage(service), returnCall)
          }
        }
      )
    }
  }

  def businessForm(service: String, businessType: String): Action[AnyContent] = Action.async { implicit request =>
    authorisedFor(service) { implicit authContext =>
      val backLink = Some(routes.BusinessVerificationController.businessVerification(service).url)
      Future.successful(
        businessType match {
          case "SOP" => Ok(templateSOP(soleTraderForm, authContext.isAgent, service, businessType, backLink, request.host + request.uri))
          case "LTD" => Ok(templateLTD(limitedCompanyForm, authContext.isAgent, service, businessType, backLink, request.host + request.uri))
          case "UIB" => Ok(templateUIB(unincorporatedBodyForm, authContext.isAgent, service, businessType, backLink, request.host + request.uri))
          case "OBP" => Ok(templateOBP(ordinaryBusinessPartnershipForm, authContext.isAgent, service, businessType, backLink, request.host + request.uri))
          case "LLP" => Ok(templateLLP(limitedLiabilityPartnershipForm, authContext.isAgent, service, businessType, backLink, request.host + request.uri))
          case "LP" => Ok(templateLP(limitedPartnershipForm, authContext.isAgent, service, businessType, backLink, request.host + request.uri))
          case "UT" => Ok(templateLTD(limitedCompanyForm, authContext.isAgent, service, businessType, backLink, request.host + request.uri))
          case "ULTD" => Ok(templateLTD(limitedCompanyForm, authContext.isAgent, service, businessType, backLink, request.host + request.uri))
          case "NRL" => Ok(templateNRL(nonResidentLandlordForm, authContext.isAgent, service, businessType, getNrlBackLink(service), request.host + request.uri))
        }
      )
    }
  }

  def submit(service: String, businessType: String): Action[AnyContent] = Action.async { implicit request =>
    authorisedFor(service) { implicit authContext =>
      val backLink = Some(routes.BusinessVerificationController.businessVerification(service).url)
      businessType match {
        case "UIB" => uibFormHandling(unincorporatedBodyForm, businessType, service, backLink, request.host + request.uri)
        case "SOP" => sopFormHandling(soleTraderForm, businessType, service, backLink, request.host + request.uri)
        case "LLP" => llpFormHandling(limitedLiabilityPartnershipForm, businessType, service, backLink, request.host + request.uri)
        case "LP" => lpFormHandling(limitedPartnershipForm, businessType, service, backLink, request.host + request.uri)
        case "OBP" => obpFormHandling(ordinaryBusinessPartnershipForm, businessType, service, backLink, request.host + request.uri)
        case "LTD" => ltdFormHandling(limitedCompanyForm, businessType, service, backLink, request.host + request.uri)
        case "UT" => ltdFormHandling(limitedCompanyForm, businessType, service, backLink, request.host + request.uri)
        case "ULTD" => ltdFormHandling(limitedCompanyForm, businessType, service, backLink, request.host + request.uri)
        case "NRL" => nrlFormHandling(nonResidentLandlordForm, businessType, service, getNrlBackLink(service), request.host + request.uri)
      }
    }
  }

  def detailsNotFound(service: String, businessType: String): Action[AnyContent] = Action.async { implicit request =>
    authorisedFor(service) { implicit authContext =>
      val backLink = Some(routes.BusinessVerificationController.businessForm(service, businessType).url)
      Future.successful(Ok(
        templateDetailsNotFound(authContext.isAgent, service, businessType,
          backLink, request.host + request.uri)
      ))
    }
  }

  private def uibFormHandling(unincorporatedBodyForm: Form[UnincorporatedMatch], businessType: String, service: String, backLink: Option[String], referrer: String)
                             (implicit authContext: StandardAuthRetrievals, req: Request[AnyContent]): Future[Result] = {
    unincorporatedBodyForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(templateUIB(formWithErrors, authContext.isAgent, service, businessType, backLink, referrer))),
      unincorporatedFormData => {
        val organisation = Organisation(unincorporatedFormData.businessName, UnincorporatedBody)
        businessMatchingService.matchBusinessWithOrganisationName(isAnAgent = authContext.isAgent,
          organisation = organisation, utr = unincorporatedFormData.cotaxUTR, service = service) flatMap { returnedResponse =>
          val validatedReviewDetails = returnedResponse.validate[ReviewDetails].asOpt
          validatedReviewDetails match {
            case Some(_) =>
              redirectWithBackLink(reviewDetailsController.controllerId,
                controllers.routes.ReviewDetailsController.businessDetails(service),
                Some(controllers.routes.BusinessVerificationController.businessForm(service, businessType).url)
              )
            case None =>
              Future.successful(Redirect(controllers.routes.BusinessVerificationController.detailsNotFound(service, businessType)))
          }
        }
      }
    )
  }

  private def sopFormHandling(soleTraderForm: Form[SoleTraderMatch], businessType: String, service: String, backLink: Option[String], referrer: String)
                             (implicit authContext: StandardAuthRetrievals, req: Request[AnyContent]): Future[Result] = {
    soleTraderForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(templateSOP(formWithErrors, authContext.isAgent, service, businessType, backLink, referrer))),
      soleTraderFormData => {
        val individual = Individual(soleTraderFormData.firstName, soleTraderFormData.lastName, None)
        businessMatchingService.matchBusinessWithIndividualName(isAnAgent = authContext.isAgent,
          individual = individual, saUTR = soleTraderFormData.saUTR, service = service) flatMap { returnedResponse =>
          val validatedReviewDetails = returnedResponse.validate[ReviewDetails].asOpt
          validatedReviewDetails match {
            case Some(_) =>
              redirectWithBackLink(reviewDetailsController.controllerId,
                controllers.routes.ReviewDetailsController.businessDetails(service),
                Some(controllers.routes.BusinessVerificationController.businessForm(service, businessType).url)
              )
            case None =>
              Future.successful(Redirect(controllers.routes.BusinessVerificationController.detailsNotFound(service, businessType)))
          }
        }
      }
    )
  }

  private def llpFormHandling(limitedLiabilityPartnershipForm: Form[LimitedLiabilityPartnershipMatch],
                              businessType: String,
                              service: String,
                              backLink: Option[String], referrer: String)
                             (implicit authContext: StandardAuthRetrievals, req: Request[AnyContent]): Future[Result] = {
    limitedLiabilityPartnershipForm.bindFromRequest.fold(
      formWithErrors => currentBackLink.map(implicit backLink =>
        BadRequest(templateLLP(formWithErrors, authContext.isAgent, service, businessType, backLink, referrer))
      ),
      llpFormData => {
        val organisation = Organisation(llpFormData.businessName, Llp)
        businessMatchingService.matchBusinessWithOrganisationName(isAnAgent = authContext.isAgent,
          organisation = organisation, utr = llpFormData.psaUTR, service = service) flatMap { returnedResponse =>
          val validatedReviewDetails = returnedResponse.validate[ReviewDetails].asOpt
          validatedReviewDetails match {
            case Some(_) =>
              redirectWithBackLink(reviewDetailsController.controllerId,
                controllers.routes.ReviewDetailsController.businessDetails(service),
                Some(controllers.routes.BusinessVerificationController.businessForm(service, businessType).url)
              )
            case None =>
              Future.successful(Redirect(controllers.routes.BusinessVerificationController.detailsNotFound(service, businessType)))
          }
        }
      }
    )
  }

  private def lpFormHandling(limitedPartnershipForm: Form[LimitedPartnershipMatch], businessType: String, service: String, backLink: Option[String], referrer: String)
                            (implicit authContext: StandardAuthRetrievals, req: Request[AnyContent]): Future[Result] = {
    limitedPartnershipForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(templateLP(formWithErrors, authContext.isAgent, service, businessType, backLink, referrer))),
      lpFormData => {
        val organisation = Organisation(lpFormData.businessName, Partnership)
        businessMatchingService.matchBusinessWithOrganisationName(isAnAgent = authContext.isAgent,
          organisation = organisation, utr = lpFormData.psaUTR, service = service) flatMap { returnedResponse =>
          val validatedReviewDetails = returnedResponse.validate[ReviewDetails].asOpt
          validatedReviewDetails match {
            case Some(_) =>
              redirectWithBackLink(reviewDetailsController.controllerId,
                controllers.routes.ReviewDetailsController.businessDetails(service),
                Some(controllers.routes.BusinessVerificationController.businessForm(service, businessType).url)
              )
            case None =>
              Future.successful(Redirect(controllers.routes.BusinessVerificationController.detailsNotFound(service, businessType)))
          }
        }
      }
    )
  }

  private def obpFormHandling(ordinaryBusinessPartnershipForm: Form[OrdinaryBusinessPartnershipMatch],
                              businessType: String,
                              service: String,
                              backLink: Option[String], referrer: String)
                             (implicit authContext: StandardAuthRetrievals, req: Request[AnyContent]): Future[Result] = {
    ordinaryBusinessPartnershipForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(templateOBP(formWithErrors, authContext.isAgent, service, businessType, backLink, referrer))),
      obpFormData => {
        val organisation = Organisation(obpFormData.businessName, Partnership)
        businessMatchingService.matchBusinessWithOrganisationName(isAnAgent = authContext.isAgent,
          organisation = organisation, utr = obpFormData.psaUTR, service = service) flatMap { returnedResponse =>
          val validatedReviewDetails = returnedResponse.validate[ReviewDetails].asOpt
          validatedReviewDetails match {
            case Some(_) =>
              redirectWithBackLink(reviewDetailsController.controllerId,
                controllers.routes.ReviewDetailsController.businessDetails(service),
                Some(controllers.routes.BusinessVerificationController.businessForm(service, businessType).url)
              )
            case None =>
              Future.successful(Redirect(controllers.routes.BusinessVerificationController.detailsNotFound(service, businessType)))
          }
        }
      }
    )
  }

  private def ltdFormHandling(limitedCompanyForm: Form[LimitedCompanyMatch],
                              businessType: String,
                              service: String,
                              backLink: Option[String], referrer: String)
                             (implicit authContext: StandardAuthRetrievals, req: Request[AnyContent]): Future[Result] = {
    limitedCompanyForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(templateLTD(formWithErrors, authContext.isAgent, service, businessType, backLink, referrer))),
      limitedCompanyFormData => {
        val organisation = Organisation(limitedCompanyFormData.businessName, CorporateBody)
        businessMatchingService.matchBusinessWithOrganisationName(isAnAgent = authContext.isAgent,
          organisation = organisation, utr = limitedCompanyFormData.cotaxUTR, service = service) flatMap { returnedResponse =>
          val validatedReviewDetails = returnedResponse.validate[ReviewDetails].asOpt
          validatedReviewDetails match {
            case Some(_) =>
              redirectWithBackLink(reviewDetailsController.controllerId,
                controllers.routes.ReviewDetailsController.businessDetails(service),
                Some(controllers.routes.BusinessVerificationController.businessForm(service, businessType).url)
              )
            case None =>
              Future.successful(Redirect(controllers.routes.BusinessVerificationController.detailsNotFound(service, businessType)))
          }
        }
      }
    )
  }

  private def nrlFormHandling(nrlForm: Form[NonResidentLandlordMatch],
                              businessType: String,
                              service: String,
                              backLink: Option[String], referrer: String)
                             (implicit authContext: StandardAuthRetrievals, req: Request[AnyContent]): Future[Result] = {
    nrlForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(templateNRL(formWithErrors, authContext.isAgent, service, businessType, backLink, referrer))),
      nrlFormData => {
        val organisation = Organisation(nrlFormData.businessName, CorporateBody)
        businessMatchingService.matchBusinessWithOrganisationName(isAnAgent = authContext.isAgent,
          organisation = organisation, utr = nrlFormData.saUTR, service = service) flatMap { returnedResponse =>
          val validatedReviewDetails = returnedResponse.validate[ReviewDetails].asOpt
          validatedReviewDetails match {
            case Some(_) =>
              redirectWithBackLink(reviewDetailsController.controllerId,
                controllers.routes.ReviewDetailsController.businessDetails(service),
                Some(controllers.routes.BusinessVerificationController.businessForm(service, businessType).url)
              )
            case None =>
              Future.successful(Redirect(controllers.routes.BusinessVerificationController.detailsNotFound(service, businessType)))
          }
        }
      }
    )
  }

  private def getNrlBackLink(service: String) = Some(controllers.nonUKReg.routes.PaySAQuestionController.view(service).url)

}
