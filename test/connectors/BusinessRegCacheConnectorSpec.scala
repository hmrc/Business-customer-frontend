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

package connectors

import config.ApplicationConfig
import models.ReviewDetails
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.{OneServerPerSuite, PlaySpec}
import play.api.libs.json.Json
import play.api.mvc.MessagesControllerComponents
import play.api.test.Helpers._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.cache.client.{CacheMap, SessionCache}
import uk.gov.hmrc.http.logging.SessionId
import uk.gov.hmrc.play.bootstrap.http.DefaultHttpClient

import scala.concurrent.Future

class BusinessRegCacheConnectorSpec extends PlaySpec with OneServerPerSuite with MockitoSugar  with BeforeAndAfterEach{

  val mockSessionCache = mock[SessionCache]

  case class FormData(name: String)

  object FormData {
    implicit val formats = Json.format[FormData]
  }

  val formId = "form-id"
  val formIdNotExist = "no-form-id"

  val formData = FormData("some-data")

  val formDataJson = Json.toJson(formData)

  val cacheMap = CacheMap(id = formId, Map("date" -> formDataJson))

  override def beforeEach: Unit = {
    reset(mockSessionCache)
  }

  val appConfig = app.injector.instanceOf[ApplicationConfig]
  implicit val mcc = app.injector.instanceOf[MessagesControllerComponents]

  val mockHttpClient = mock[DefaultHttpClient]

  object TestDataCacheConnector extends BusinessRegCacheConnector(
    mockHttpClient,
    appConfig
  ) {
    override val sourceId: String = "BC_NonUK_Business_Details"
  }

  implicit val hc: HeaderCarrier = HeaderCarrier(sessionId = Some(SessionId("test")))

  "BusinessRegCacheConnector" must {

    "fetchAndGetBusinessDetailsForSession" must {

      "return Some" when {
        "formId of the cached form does exist for defined data type" in {
          when(mockHttpClient.GET[CacheMap](ArgumentMatchers.any())(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(Future.successful(CacheMap("test", Map(formIdNotExist -> Json.toJson(formData)))))

          await(TestDataCacheConnector.fetchAndGetCachedDetails[FormData](formIdNotExist)) must be(Some(formData))
        }
      }
    }

    "save form data" when {
      "valid form data with a valid form id is passed" in {
        when(mockHttpClient.PUT[FormData, CacheMap]
          (ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
          .thenReturn(Future.successful(CacheMap("test", Map(formIdNotExist -> Json.toJson(formData)))))

        await(TestDataCacheConnector.cacheDetails[FormData](formId, formData)) must be(formData)
      }
    }
  }
}
