import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.communication.message.tencentCustomMarkdown
import com.diyigemt.arona.communication.message.h1

val builder = MessageChainBuilder()

val md = tencentCustomMarkdown {
  h1("别学我说话")
}

builder.append(md)
