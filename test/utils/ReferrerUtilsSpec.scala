/*
 * Copyright 2021 HM Revenue & Customs
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

package utils

import config.ApplicationConfig
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.i18n.MessagesApi
import play.api.mvc.{AnyContent, MessagesRequest}
import play.api.test.{FakeRequest, Injecting}
import uk.gov.hmrc.play.test.UnitSpec
import utils.ReferrerUtils.getReferrer

class ReferrerUtilsSpec extends UnitSpec with GuiceOneAppPerTest with Injecting {

  "getReferrer" should {

    "return an encoded url containing the request app host and request path" in {
      implicit val appConfig: ApplicationConfig = inject[ApplicationConfig]
      implicit val request: MessagesRequest[AnyContent] =
        new MessagesRequest[AnyContent](FakeRequest("GET", "/a-uri"), inject[MessagesApi])

      getReferrer() should be("http%3A%2F%2Flocalhost%3A9923%2Fa-uri")
    }

  }

}
