package io.mo.xatype.dict

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OnlineDictClientTest {
    @Test fun `socket errors become short Chinese messages without addresses`() {
        assertEquals("无法连接 GitHub，请检查网络后重试",
            OnlineDictClient.describe(ConnectException("failed to connect to api.github.com/198.18.0.22 (port 443)")))
        assertEquals("连接 GitHub 超时，请检查网络后重试", OnlineDictClient.describe(SocketTimeoutException("timeout")))
        assertEquals("无法解析 GitHub 地址，请检查网络", OnlineDictClient.describe(UnknownHostException("api.github.com")))
    }

    @Test fun `own Chinese messages are kept and unknown English ones are hidden`() {
        assertEquals("下载不完整，请重试", OnlineDictClient.describe(IOException("下载不完整，请重试")))
        assertEquals("网络异常，请稍后重试", OnlineDictClient.describe(IOException("unexpected end of stream")))
    }
}
