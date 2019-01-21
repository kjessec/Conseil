package tech.cryptonomic.conseil.routes

import akka.actor.ActorSystem
import akka.http.caching.LfuCache
import akka.http.caching.scaladsl.{Cache, CachingSettings}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.CachingDirectives._
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import com.typesafe.scalalogging.LazyLogging
import tech.cryptonomic.conseil.config.HttpCacheConfiguration
import tech.cryptonomic.conseil.config.Platforms.PlatformsConfiguration
import tech.cryptonomic.conseil.tezos.TezosPlatformDiscoveryOperations
import tech.cryptonomic.conseil.util.JsonUtil._
import tech.cryptonomic.conseil.util.{ConfigUtil, RouteHandling}

import scala.concurrent.ExecutionContext

/** Companion object providing apply implementation */
object PlatformDiscovery {
  def apply(platforms: PlatformsConfiguration, caching: HttpCacheConfiguration)(implicit apiExecutionContext: ExecutionContext): PlatformDiscovery =
    new PlatformDiscovery(platforms, caching)(apiExecutionContext)
}

/**
  * Platform discovery routes.
  *
  * @param config              configuration object
  * @param apiExecutionContext is used to call the async operations exposed by the api service
  */
class PlatformDiscovery(config: PlatformsConfiguration, caching: HttpCacheConfiguration)(implicit apiExecutionContext: ExecutionContext) extends LazyLogging with RouteHandling {

  /** default caching settings*/
  private val defaultCachingSettings: CachingSettings = CachingSettings(caching.cacheConfig)

  /** simple partial function for filtering */
  private val requestCacheKeyer: PartialFunction[RequestContext, Uri] = {
    case r: RequestContext => r.request.uri
  }

  /** LFU caching settings */
  private val cachingSettings: CachingSettings =
    defaultCachingSettings.withLfuCacheSettings(defaultCachingSettings.lfuCacheSettings)

  /** LFU cache */
  private val lfuCache: Cache[Uri, RouteResult] = LfuCache(cachingSettings)

  /** Metadata route */
  val route: Route =
    get {
      cache(lfuCache, requestCacheKeyer) {
        pathPrefix("platforms") {
          complete(toJson(ConfigUtil.getPlatforms(config)))
        } ~
          pathPrefix(Segment) { platform =>
            pathPrefix("networks") {
              validatePlatform(config, platform) {
                pathEnd {
                  complete(toJson(ConfigUtil.getNetworks(config, platform)))
                }
              }
            } ~ pathPrefix(Segment) { network =>
              validatePlatformAndNetwork(config, platform, network) {
                pathPrefix("entities") {
                  pathEnd {
                    completeWithJson(TezosPlatformDiscoveryOperations.getEntities(network))
                  }
                } ~ pathPrefix(Segment) { entity =>
                  validateEntity(entity) {
                    pathPrefix("attributes") {
                      pathEnd {
                        completeWithJson(TezosPlatformDiscoveryOperations.getTableAttributes(entity))
                      }
                    } ~ pathPrefix(Segment) { attribute =>
                      validateAttributes(entity, attribute) {
                        pathEnd {
                          completeWithJson(TezosPlatformDiscoveryOperations.listAttributeValues(entity, attribute))
                        } ~ pathPrefix(Segment) { filter =>
                          pathEnd {
                            completeWithJson(TezosPlatformDiscoveryOperations.listAttributeValues(entity, attribute, Some(filter)))
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
      }
    }
}
