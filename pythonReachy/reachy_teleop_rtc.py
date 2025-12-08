#!/usr/bin/env python3
"""
Listen on Agora RTC data stream and print any messages received.

- Uses App ID ca13c07831364faf988d67a6a15032f4
- Joins channel "temi_hackathon"
- Prints raw text of each data-stream message.
"""

import logging
import os
import sys
import time

# ----------------------------------------------------------------------------
# Make sure we can import agorartc from local Agora-Python-SDK
# ----------------------------------------------------------------------------

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
SDK_DIR = os.path.join(THIS_DIR, "Agora-Python-SDK")

if SDK_DIR not in sys.path:
    sys.path.insert(0, SDK_DIR)

import agorartc  # noqa: E402

# ----------------------------------------------------------------------------
# Agora config
# ----------------------------------------------------------------------------

AGORA_APP_ID = "ca13c07831364faf988d67a6a15032f4"
CHANNEL_NAME = "temi_hackathon"
TEMP_TOKEN = ""   # keep empty if your project doesn't require tokens
LOCAL_UID = 0     # let Agora assign a uid


# ----------------------------------------------------------------------------
# RTC event handler that just prints messages
# ----------------------------------------------------------------------------

class PrintOnlyEventHandler(agorartc.RtcEngineEventHandlerBase):
    def __init__(self):
        super().__init__()
        self.log = logging.getLogger("PrintOnlyHandler")

    def onJoinChannelSuccess(self, channel, uid, elapsed):
        self.log.info("Joined channel '%s' as uid=%s", channel, uid)

    def onError(self, err, msg):
        self.log.error("Agora error %s: %s", err, msg)

    def onStreamMessage(self, uid, streamId, data, length):
        """
        Called when we receive a data-stream message.
        Just decode and print it.
        """
        try:
            if isinstance(data, (bytes, bytearray)):
                text = data.decode("utf-8", errors="replace")
            else:
                text = str(data)
        except Exception as e:
            self.log.warning("Failed to decode message from uid=%s: %s", uid, e)
            return

        print(f"[STREAM] from uid={uid}, streamId={streamId}: {text}")

    def onStreamMessageError(self, uid, streamId, code, missed, cached):
        self.log.error(
            "StreamMessageError uid=%s streamId=%s code=%s missed=%s cached=%s",
            uid, streamId, code, missed, cached,
        )


# ----------------------------------------------------------------------------
# Simple client wrapper
# ----------------------------------------------------------------------------

class AgoraPrintClient:
    def __init__(self):
        self.log = logging.getLogger("AgoraPrintClient")

        self.rtc = agorartc.createRtcEngineBridge()
        self.handler = PrintOnlyEventHandler()
        self.rtc.initEventHandler(self.handler)

        area_code = agorartc.AREA_CODE_GLOB & 0xFFFFFFFF
        ret = self.rtc.initialize(AGORA_APP_ID, None, area_code)
        if ret != 0:
            self.log.error("rtc.initialize failed with code %s", ret)
            sys.exit(1)

        # No real need for video, but enabling doesn't hurt
        self.rtc.enableVideo()

        # Optional: create a local data stream if you want to send later
        try:
            stream_id = self.rtc.createDataStream(True, True)
            self.log.info("Created local data stream id=%s", stream_id)
        except Exception as e:
            self.log.warning("Unable to create local data stream: %s", e)

    def join(self):
        self.log.info("Joining RTC channel '%s' with AppID=%s", CHANNEL_NAME, AGORA_APP_ID)
        ret = self.rtc.joinChannel(TEMP_TOKEN, CHANNEL_NAME, "", LOCAL_UID)
        if ret != 0:
            self.log.error("joinChannel failed with code %s", ret)
            sys.exit(1)

    def loop(self):
        self.log.info("Listening for data-stream messages. Ctrl+C to quit.")
        try:
            while True:
                time.sleep(1.0)
        except KeyboardInterrupt:
            self.log.info("Shutting down...")
            self.rtc.leaveChannel()
            self.rtc.release()


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="[%(asctime)s] %(name)s %(levelname)s: %(message)s",
    )

    client = AgoraPrintClient()
    client.join()
    client.loop()


if __name__ == "__main__":
    main()
