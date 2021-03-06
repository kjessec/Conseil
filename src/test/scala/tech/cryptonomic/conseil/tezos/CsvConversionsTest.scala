package tech.cryptonomic.conseil.tezos

import org.scalatest.{Matchers, WordSpec}
import tech.cryptonomic.conseil.generic.chain.DataTypes.QueryResponse

class CsvConversionsTest extends WordSpec with Matchers {

  import tech.cryptonomic.conseil.util.Conversion.Syntax._
  import tech.cryptonomic.conseil.routes.openapi.CsvConversions._

  "CSV conversions" should {

      "correctly convert List[QueryResponse] to CsvString" in {
        val queryResponse: List[QueryResponse] =
          List(Map("a" -> Some("1"), "b" -> Some(2), "c" -> Some(3.0), "d" -> None))
        val expectedResult: CsvString =
          """
          |a,b,c,d
          |1,2,3.0,null
        """.stripMargin.trim

        val result = queryResponse.convertTo[CsvString]

        result shouldBe expectedResult
      }

      "correctly convert empty list" in {
        val queryResponse: List[QueryResponse] = List.empty
        val expectedResult: CsvString = ""

        val result = queryResponse.convertTo[CsvString]

        result shouldBe expectedResult
      }
    }

}
