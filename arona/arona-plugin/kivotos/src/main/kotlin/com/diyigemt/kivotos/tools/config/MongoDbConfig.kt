package com.diyigemt.kivotos.tools.config

import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.value

object MongoDbConfig : AutoSavePluginData("mongodb") {
  val host by value("127.0.0.1")
  val port by value(27017)
  val user by value("kivotos")
  val password by value("kivotos")
  val db by value("kivotos")
  fun toConnectionString() = "mongodb://$user:$password@$host:$port/?authSource=$db"
}
