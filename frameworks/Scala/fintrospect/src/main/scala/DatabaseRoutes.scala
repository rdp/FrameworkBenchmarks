import com.twitter.finagle.Service
import com.twitter.finagle.http.Method.Get
import com.twitter.finagle.http.Status.{NotFound, Ok}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.mysql.Parameter.wrap
import com.twitter.finagle.mysql.{Client, IntValue, Result, ResultSet}
import com.twitter.util.Future.collect
import io.fintrospect.RouteSpec.RequestValidation
import io.fintrospect.RouteSpec.RequestValidation.none
import io.fintrospect.formats.Json4sJackson.JsonFormat.{array, number, obj}
import io.fintrospect.formats.Json4sJackson.ResponseBuilder.implicits._
import io.fintrospect.parameters.ParameterSpec.int
import io.fintrospect.parameters.Query
import io.fintrospect.{RouteSpec, ServerRoutes}
import org.json4s.JValue

import scala.language.reflectiveCalls
import scala.util.Random

object DatabaseRoutes {

  private val toJson: PartialFunction[Result, Option[JValue]] = {
    case rs: ResultSet => rs.rows.headOption
      .map(row => {
        val IntValue(id) = row("id").get
        val IntValue(random) = row("randomNumber").get
        obj("id" -> number(id), "randomNumber" -> number(random))
      })
    case _ => None
  }

  private def generateRandomNumber = Random.nextInt(9999) + 1

  def apply(database: Client) = {
    val getStatement = database.prepare("SELECT id, randomNumber FROM world WHERE id = ?")
    val updateStatement = database.prepare("UPDATE world SET randomNumber = ? WHERE id = ?")

    val queryRoute = RouteSpec(validation = none).at(Get) / "db" bindTo Service.mk {
      r: Request => getStatement(generateRandomNumber)
        .map(toJson)
        .map(_.map(Ok(_)).getOrElse(NotFound()).build())
    }

    val numberOfQueries = Query.optional(int("queries").map(_.max(1).min(500)))

    val multipleRoute = RouteSpec()
      .taking(numberOfQueries)
      .at(Get) / "queries" bindTo Service.mk {
      r: Request => {
        collect(1.to((numberOfQueries <-- r).getOrElse(1))
          .map(i => getStatement(generateRandomNumber).map(toJson)))
          .map(f => f.flatMap(_.toSeq))
          .flatMap(c => Ok(array(c)))
      }
    }

    val updateRoute = RouteSpec()
      .taking(numberOfQueries)
      .at(Get) / "updates" bindTo Service.mk {
      r: Request => {
        collect(1.to((numberOfQueries <-- r).getOrElse(1))
          .map(i => {
            val id = generateRandomNumber
            updateStatement(generateRandomNumber, id)
              .flatMap(_ => getStatement(id))
              .map(toJson)
          }))
          .map(f => f.flatMap(_.toSeq))
          .flatMap(c => Ok(array(c)))
      }
    }

    new ServerRoutes[Request, Response] {
      add(queryRoute)
      add(multipleRoute)
      add(updateRoute)
    }
  }
}
