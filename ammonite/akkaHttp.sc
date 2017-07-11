import coursier.core.Authentication, coursier.MavenRepository

interp.repositories() ++= Seq(MavenRepository(
  "http://bblfish.net/work/repo/snapshots/"
  ))

@

import scala.concurrent.ExecutionContext
import com.typesafe.config._

import $ivy.`org.w3::banana-sesame:0.8.4-SNAPSHOT`
import org.w3.banana._
import org.w3.banana.syntax._
import org.w3.banana.sesame.Sesame
import Sesame._
import Sesame.ops._

import $ivy.`com.typesafe.akka::akka-http:10.0.9`
//import $ivy.`ch.qos.logback:logback-classic:1.2.3`

import akka.actor.ActorSystem
import akka.actor.SupervisorStrategy
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{Uri=>AkkaUri,_}

import akka.stream.{ActorMaterializer, ActorMaterializerSettings,Supervision,_}
import akka.stream.scaladsl._
import akka.{ NotUsed, Done }
import akka.event.Logging

import scala.concurrent.Future
import scala.util.control.NoStackTrace 
import scala.util.control.NonFatal
import scala.util.{Try,Success,Failure}
//see http://doc.akka.io/docs/akka/snapshot/scala/general/configuration.html#listing-of-the-reference-configuration
val shortScriptConf = ConfigFactory.parseString("""
   |akka {
   |   loggers = ["akka.event.Logging$DefaultLogger"]
   |   logging-filter = "akka.event.DefaultLoggingFilter"
   |   loglevel = "ERROR"
   |}
 """.stripMargin)
implicit val system = ActorSystem("akka_ammonite_script",shortScriptConf)
val log = Logging(system.eventStream, "banana-rdf")
implicit val materializer = ActorMaterializer(
                ActorMaterializerSettings(system).withSupervisionStrategy(Supervision.resumingDecider))
implicit val ec: ExecutionContext = system.dispatcher

//just to play with 
val me = AkkaUri("http://bblfish.net/people/henry/card#me")

object RdfMediaTypes {
   import akka.http.scaladsl.model
   import model.ContentType
   import model.MediaType.{text,applicationWithOpenCharset,applicationWithFixedCharset}
   import model.HttpCharsets._
   import org.w3.banana.io.RDFReader 
   import akka.http.scaladsl.unmarshalling.{Unmarshaller,PredefinedFromEntityUnmarshallers,FromEntityUnmarshaller}
   import scala.util.{Try,Success,Failure}

    case class NoUnmarshallerException(mime: ContentType, msg: String) extends java.lang.RuntimeException with NoStackTrace with Product with Serializable

   val `text/turtle` = text("turtle","ttl")
   val `application/rdf+xml` = applicationWithOpenCharset("rdf+xml","rdf")
   val `application/ntriples` = applicationWithFixedCharset("ntriples",`UTF-8`,"nt")
   val `application/ld+json` = applicationWithOpenCharset("ld+json","jsonld")

   
   def rdfUnmarshaller(requestUri: AkkaUri): FromEntityUnmarshaller[Try[Rdf#Graph]] = {
        import Unmarshaller._
        //todo: use non blocking parsers
        val rdfunmarshaller = PredefinedFromEntityUnmarshallers.stringUnmarshaller mapWithInput { (entity, string) ⇒ 
           val reader = entity.contentType.mediaType match { //<- this needs to be tuned!
              case `text/turtle` => turtleReader
              case `application/rdf+xml` => rdfXMLReader
              case `application/ntriples` => ntriplesReader
              case `application/ld+json` => jsonldReader
           }
           reader.read(new java.io.StringReader(string),requestUri.toString)
        }
        rdfunmarshaller.forContentTypes(`text/turtle`,`application/rdf+xml`,`application/ntriples`,`application/ld+json`) 
  }

}

object Web {
    trait WebException extends java.lang.RuntimeException with NoStackTrace with Product with Serializable
    case class HTTPException(resourceUri: String, msg: String) extends WebException
    case class ConnectionException(resourceUri: String, e: Throwable) extends WebException
    case class NodeTranslationException(graphLoc: String, problemNode: Rdf#Node, e: Throwable) extends WebException
    case class ParseException(resourceUri: String, 
                              status: StatusCode, 
                              responseHeaders: Seq[HttpHeader],
                              contentType: ContentType,
                              initialContent: Try[String],
                              e: Throwable) extends WebException

   implicit class UriW(val uri: AkkaUri)  extends AnyVal {
         def fragmentLess: AkkaUri = 
            if (uri.fragment.isEmpty) uri else uri.copy(fragment=None)
         
         def toRdfUri: Rdf#URI = URI(uri.toString)
    }

   def rdfRequest(uri: AkkaUri): HttpRequest = {
      import RdfMediaTypes._  
      import akka.http.scaladsl.model.headers.Accept
      HttpRequest(uri=uri.fragmentLess)
           .addHeader(Accept(`text/turtle`,`application/rdf+xml`,
                             `application/ntriples`,`application/ld+json`))
   }

   //interpreted HttpResponse
   case class HttpRes[C](origin: AkkaUri, status: StatusCode, headers: Seq[HttpHeader], content: C) {
      def map[D](f: C => D) = this.copy(content=f(content))
   }


   implicit class HttResPG(val h: HttpRes[PointedGraph[Rdf]]) extends AnyVal {
    def jump(rel: Sesame#URI)(implicit web: Web): List[Future[HttpRes[PointedGraph[Rdf]]]] =
          (h.content/rel).toList.map{ pg =>
              if (pg.pointer.isURI) try {
                   web.pointedGET(AkkaUri(pg.pointer.toString))
                 } catch {
                   case NonFatal(e) => Future.failed(NodeTranslationException(h.origin.toString, pg.pointer,e))
                 }
              else Future.successful(h.copy(content=pg))
       }
    } 

    import scala.collection.immutable 
    def uriSource(uris: AkkaUri*): Source[AkkaUri,NotUsed] =  
         Source(immutable.Seq(uris:_*).to[collection.immutable.Iterable])

   def neverFail[X](fut: Future[X]): Future[Try[X]] = fut.transform(Success(_))
   def reduceFlowFuture[X](n: Int=1) = Flow[Future[X]].mapAsyncUnordered(n){f: Future[X] =>
              log.info("received a future. Will now reapper as object in Stream")
              f
   }

   def goodFriendGraph(me: AkkaUri) = {
       import scala.collection.immutable
       val mine = uriSource(me)
       val sourceJumpId = mine.mapAsync(1){uri => log.info("initial jump id"); web.pointedGET(uri)}
       val sourceJumpFutKn = sourceJumpId.mapConcat{ hrpg => 
           val seqFut = hrpg.jump(foaf.knows).to[immutable.Iterable] 
           log.info(s"jumped <${me.toString}>/foaf.knows and received a sequence of ${seqFut.size} Futures")
           seqFut.map(neverFail(_))
       }
       val sourceJumpTryKn = sourceJumpFutKn.via(reduceFlowFuture(50))
       val sinkFold2 = Sink.fold[List[Try[Web.HttpRes[PointedGraph[Rdf]]]],
                                 Try[Web.HttpRes[PointedGraph[Rdf]]]](List()){ case (l,t)=>
                                   log.info(s"in Sink.fold. Appending $t to $l")
                                   t::l
                                  }
       sourceJumpTryKn.toMat(sinkFold2)(Keep.right)
   }
 
}

class Web(implicit ec: ExecutionContext) {
   import Web._


   def GET(uri: AkkaUri, maxRedirect: Int=4): Future[HttpResponse] = httpRequire(rdfRequest(uri),maxRedirect)
 
   //todo: add something to the response re number of redirects
   //see: https://github.com/akka/akka-http/issues/195
   def httpRequire(req: HttpRequest, maxRedirect: Int = 4)(implicit 
      system: ActorSystem, mat: Materializer): Future[HttpResponse] = {
      try {
         import StatusCodes._
         Http().singleRequest(req)
               .recoverWith{case e=>Future.failed(ConnectionException(req.uri.toString,e))}
               .flatMap { resp =>
            resp.status match {
              case Found | SeeOther | TemporaryRedirect | PermanentRedirect => {
                  log.info(s"received a ${resp.status} for ${req.uri}")
                  resp.header[headers.Location].map { loc =>
                  val newReq = req.copy(uri = loc.uri)
                  if (maxRedirect > 0) httpRequire(newReq, maxRedirect - 1) else Http().singleRequest(newReq)
                 }.getOrElse(Future.failed(HTTPException(req.uri.toString,s"Location header not found on ${resp.status} for ${req.uri}")))
              }
              case _ => Future.successful(resp)
            }
         }
      } catch {
         case NonFatal(e) => Future.failed(ConnectionException(req.uri.toString,e))
      }
   }
   
  

   def GETrdf(uri: AkkaUri): Future[HttpRes[Rdf#Graph]] = {
     import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
     import akka.util.ByteString

     def decode(bytes: ByteString, entity: ResponseEntity) = {
        val charBuffer = Unmarshaller.bestUnmarshallingCharsetFor(entity).nioCharset.decode(bytes.asByteBuffer)
        val array = new Array[Char](charBuffer.length())
        charBuffer.get(array)
        new java.lang.String(array).take(210) //could be something to be set by config
     }
 
     GET(uri).flatMap {
        case HttpResponse(status,headers,entity,protocol) => { 
            def bytesF: Future[String] =  
              entity.dataBytes.take(1).runFold(ByteString.empty)({ case (acc, b) => acc ++ b }).transform{ tryByteString =>
                tryByteString.map(decode(_,entity))
              }
            
            implicit  val reqUnmarhaller = RdfMediaTypes.rdfUnmarshaller(uri)
            Unmarshal(entity).to[Try[Rdf#Graph]]
                .transformWith {
                    case Success(tryParse) => 
                          tryParse match {
                             case Success(g) => 
                                Future.successful(HttpRes[Rdf#Graph](uri,status,headers,g))
                             case Failure(e) =>  
                                bytesF.transform{ tryParse =>
                                  Failure(ParseException(uri.toString,status,headers,entity.contentType,tryParse,e))
                                }
                          }
                    case Failure(e) => {
                        bytesF.transform{ tryParse =>
                           Failure(ParseException(uri.toString,status,headers,entity.contentType,tryParse,e)) 
                        }
                    }
            }
        }
      }
    }

    def pointedGET(uri: AkkaUri): Future[HttpRes[PointedGraph[Rdf]]] = 
         GETrdf(uri).map(httpresg=>httpresg.map(g=>PointedGraph[Rdf](URI(uri.toString),g)))
    

}

//make life easier in the shell by setting up the environment
implicit val web = new Web()
val foaf = FOAFPrefix[Rdf]
import $exec.FutureWrapper

