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
        10,
        listOf(
          GachaResultItem("Student_Portrait_Serina.png", true),
          GachaResultItem("Student_Portrait_Serina.png"),
          GachaResultItem("Student_Portrait_Serina.png"),
          GachaResultItem("Student_Portrait_Serina.png"),
          GachaResultItem("Student_Portrait_Serina.png"),
          GachaResultItem("Student_Portrait_Serina.png"),
          GachaResultItem("Student_Portrait_Serina.png"),
          GachaResultItem("Student_Portrait_Serina.png"),
          GachaResultItem("Student_Portrait_Momoi.png", true, rarity = StudentRarity.SR),
          GachaResultItem("Student_Portrait_Seia.png", isNew = true, isPickup = true, StudentRarity.SSR),
        )
      )
    ).also {
      it.makeImageSnapshot().encodeToData(format = EncodedImageFormat.PNG)?.bytes?.also { im ->
        Files.write(Arona.dataFolderPath.resolve("gacha.png"), im)
      }
    }
  }

}
