package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.tools.GachaResult
import com.diyigemt.arona.arona.tools.GachaResultItem
import com.diyigemt.arona.arona.tools.GachaTool
import com.diyigemt.arona.arona.tools.StudentRarity
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files

@Suppress("unused")
object GachaCommand : AbstractCommand(
  Arona,
  "十连",
  description = "抽一发十连"
) {
  suspend fun UserCommandSender.callMe() {
    GachaTool.generateGachaImage(
      GachaResult(
        "",
        11,
        listOf(
          GachaResultItem("Student_Portrait_CH0230.png", isNew = true, isPickup = true, rarity = StudentRarity.SSR),
        )
      )
    ).also {
      it.makeImageSnapshot().encodeToData(format = EncodedImageFormat.PNG)?.bytes?.also { im ->
        Files.write(Arona.dataFolderPath.resolve("gacha.png"), im)
      }
    }
  }

}
