package tech.cryptonomic.conseil.metadata

import tech.cryptonomic.conseil.config.MetadataConfiguration
import tech.cryptonomic.conseil.config.Types.{AttributeName, EntityName}
import tech.cryptonomic.conseil.generic.chain.PlatformDiscoveryTypes.AttributeCacheConfiguration
import tech.cryptonomic.conseil.util.OptionUtil.when

/** Class for extracting attribute cache configurations */
class AttributeValuesCacheConfiguration(metadataConfiguration: MetadataConfiguration) {

  /** extracts cache configuration for given attribute path */
  def getCacheConfiguration(path: AttributePath): Option[AttributeCacheConfiguration] =
    whenVisible(path) {
      metadataConfiguration
        .attribute(path)
        .flatMap(_.cacheConfig)
    }

  /** extracts cache configuration for given attribute path */
  def getCardinalityHint(path: AttributePath): Option[Int] =
    whenVisible(path) {
      metadataConfiguration
        .attribute(path)
        .flatMap(_.cardinalityHint)
    }

  /** helper method for getting information from the path */
  private def whenVisible[T](path: Path)(value: => Option[T]): Option[T] =
    when(metadataConfiguration.isVisible(path))(value).flatten

  /** extracts pair (entity, attribute) which needs to be cached */
  def getAttributesToCache: List[(EntityName, AttributeName)] =
    metadataConfiguration.allAttributes.filter {
      case (_, conf) => conf.cacheConfig.exists(_.cached)
    }.keys
      .filter(metadataConfiguration.isVisible)
      .map(path => (path.up.entity, path.attribute))
      .toList
}
