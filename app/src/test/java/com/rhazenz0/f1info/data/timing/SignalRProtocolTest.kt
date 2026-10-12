package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalRProtocolTest {
    @Test
    fun splitsAndParsesFrames() {
        val payload = "{}\u001e{\"type\":3,\"invocationId\":\"1\",\"result\":{\"LapCount\":{\"CurrentLap\":5}}}\u001e{\"type\":6}\u001e"
        val frames = SignalRProtocol.frames(payload)
        assertEquals(3, frames.size)
        assertTrue(SignalRProtocol.parse(frames[0]) is SignalRProtocol.Message.HandshakeResponse)
        val completion = SignalRProtocol.parse(frames[1]) as SignalRProtocol.Message.Completion
        assertEquals("5", completion.result.at("LapCount", "CurrentLap").text())
        assertEquals(SignalRProtocol.Message.Ping, SignalRProtocol.parse(frames[2]))
    }

    @Test
    fun parsesFeedInvocation() {
        val frame = """{"type":1,"target":"feed","arguments":["LapCount",{"CurrentLap":6},"2026-10-11T12:10:22.698Z"]}"""
        val msg = SignalRProtocol.parse(frame) as SignalRProtocol.Message.Invocation
        assertEquals("feed", msg.target)
        assertEquals("LapCount", msg.arguments[0].text())
    }

    @Test
    fun buildsSubscribeInvocation() {
        val s = SignalRProtocol.subscribe("1", listOf("TimingData", "CarData.z"))
        assertEquals(
            """{"type":1,"invocationId":"1","target":"Subscribe","arguments":[["TimingData","CarData.z"]]}""" + "\u001e",
            s,
        )
    }
}
