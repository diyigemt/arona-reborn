package com.diyigemt.arona.webui.plugins

import com.diyigemt.arona.utils.aronaConfig
import com.diyigemt.arona.utils.runSuspend
import io.github.crackthecodeabhi.kreds.connection.Endpoint
import io.github.crackthecodeabhi.kreds.connection.newClient

val redisClient by lazy {
  newClient(Endpoint(aronaConfig.redis.host, aronaConfig.redis.port)).apply {
    runSuspend {
      select(aronaConfig.redis.db)
    }
  }
}
