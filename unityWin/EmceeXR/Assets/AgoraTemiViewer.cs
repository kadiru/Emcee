using UnityEngine;
using UnityEngine.UI;
using UnityEngine.XR;
using Agora.Rtc;
using System.Text;

public class AgoraTemiViewer : MonoBehaviour
{
    [Header("Agora")]
    [SerializeField] private string appID = "ca13c07831364faf988d67a6a15032f4";
    [SerializeField] private string channelName = "temi_hackathon";
    [SerializeField] private string token = "";   // leave empty if you are not using tokens

    [Header("Video")]
    [SerializeField] private VideoSurface videoSurface;

    [Header("Overlay UI")]
    [Tooltip("UI Text overlaid on the video to show what is being sent/received.")]
    [SerializeField] private Text overlayText;

    [Header("Teleop Messaging")]
    [Tooltip("Messages per second to send over Agora (e.g. 50 for ~50 Hz).")]
    [SerializeField] private float sendRateHz = 50f;

    [Tooltip("If true, start at 1000; if false, start at 1.")]
    [SerializeField] private bool startHigh = false;

    // ---------- Teleop payload we send over Agora ----------
    [System.Serializable]
    private struct TeleopMessage
    {
        public int number;
        public bool teleop_engaged;     // trigger held
        public bool a_temi_engaged;     // button A held
        public bool b_reachy_engaged;   // button B held
        public bool funny_engaged;      // side/grip button held

        // Controller orientation
        public float pitch_deg;         // calibrated pitch
        public float roll_deg;          // roll in degrees [-180, 180]
        public float yaw_deg;           // yaw  in degrees [-180, 180]

        // Right thumbstick values (always in [-1, 1])
        public float stick_x;
        public float stick_y;
    }

    private IRtcEngine rtcEngine;
    private int dataStreamId = -1;

    // number we will send next
    private int currentValue;

    // last sent message
    private int lastSentNumber = -1;
    private bool lastSentTeleopEngaged = false;
    private bool lastSentATemiEngaged = false;
    private bool lastSentBReachyEngaged = false;
    private bool lastSentFunnyEngaged = false;
    private float lastSentPitch = 0f;
    private float lastSentRoll = 0f;
    private float lastSentYaw = 0f;
    private float lastSentStickX = 0f;
    private float lastSentStickY = 0f;

    // last received message
    private int lastReceivedNumber = -1;
    private bool lastReceivedTeleopEngaged = false;
    private bool lastReceivedATemiEngaged = false;
    private bool lastReceivedBReachyEngaged = false;
    private bool lastReceivedFunnyEngaged = false;
    private float lastReceivedPitch = 0f;
    private float lastReceivedRoll = 0f;
    private float lastReceivedYaw = 0f;
    private float lastReceivedStickX = 0f;
    private float lastReceivedStickY = 0f;
    private string lastReceivedRaw = "none";

    // local input state (buttons)
    private bool teleopPressed = false;   // trigger
    private bool aTemiPressed = false;    // A
    private bool bReachyPressed = false;  // B
    private bool funnyPressed = false;    // grip/side

    // local pose state (what we will send)
    private float currentPitchDeg = 0f;
    private float currentRollDeg = 0f;
    private float currentYawDeg = 0f;

    // local stick state (what we will send)
    private Vector2 currentStick = Vector2.zero;

    private string fireStatus = "idle";   // for display

    private bool joined = false;

    // derived from sendRateHz in Start()
    private float sendIntervalSeconds;
    private float timer = 0f;

    private void Start()
    {
        if (videoSurface == null)
        {
            videoSurface = GetComponentInChildren<VideoSurface>();
        }

        currentValue = startHigh ? 1000 : 1;

        if (overlayText != null)
        {
            overlayText.color = Color.red;  // idle = red
        }

        // Compute interval from rate, and clamp to something sane
        sendIntervalSeconds = 1.0f / Mathf.Max(1.0f, sendRateHz);
        Debug.Log($"[AgoraTemiViewer] sendRateHz={sendRateHz}, sendIntervalSeconds={sendIntervalSeconds:F4}");

        UpdateOverlayText();
        InitAgora();
    }

    private void Update()
    {
        // 1) Poll controller buttons + pose + stick every frame
        PollControllerButtonsPoseAndStick();

        // 2) Periodic send ~sendRateHz
        if (!joined || rtcEngine == null || dataStreamId < 0)
            return;

        timer += Time.deltaTime;
        if (timer >= sendIntervalSeconds)
        {
            timer -= sendIntervalSeconds; // preserve remainder for smoother rate
            SendTeleopMessage();
        }
    }

    private void OnDestroy()
    {
        if (rtcEngine != null)
        {
            rtcEngine.LeaveChannel();
            rtcEngine.Dispose();
            rtcEngine = null;
        }
    }

    // =========================================================
    // XR controller input: buttons + pose + stick
    // =========================================================
    private void PollControllerButtonsPoseAndStick()
    {
        var rightHand = InputDevices.GetDeviceAtXRNode(XRNode.RightHand);

        if (!rightHand.isValid)
        {
            // Controller lost: zero pose & stick; keep last button state as-is.
            currentPitchDeg = 0f;
            currentRollDeg  = 0f;
            currentYawDeg   = 0f;
            currentStick    = Vector2.zero;
            return;
        }

        bool triggerDown   = false;
        bool primaryDown   = false;
        bool secondaryDown = false;
        bool gripDown      = false;

        rightHand.TryGetFeatureValue(CommonUsages.triggerButton, out triggerDown);
        rightHand.TryGetFeatureValue(CommonUsages.primaryButton, out primaryDown);
        rightHand.TryGetFeatureValue(CommonUsages.secondaryButton, out secondaryDown);
        rightHand.TryGetFeatureValue(CommonUsages.gripButton, out gripDown);

        // --- teleop_engaged (fire) from trigger ---
        if (triggerDown && !teleopPressed)
        {
            teleopPressed = true;
            fireStatus = "FIRE!";

            if (overlayText != null)
                overlayText.color = Color.yellow;

            Debug.Log("[Teleop] Trigger pressed (teleop_engaged = true)");
            UpdateOverlayText();
        }
        else if (!triggerDown && teleopPressed)
        {
            teleopPressed = false;
            fireStatus = "idle";

            if (overlayText != null)
                overlayText.color = Color.red;

            Debug.Log("[Teleop] Trigger released (teleop_engaged = false)");
            UpdateOverlayText();
        }

        // --- a_temi_engaged from primary button (A) ---
        bool oldATemi = aTemiPressed;
        aTemiPressed = primaryDown;
        if (aTemiPressed != oldATemi)
        {
            Debug.Log($"[Teleop] A_Temi changed: {aTemiPressed}");
            UpdateOverlayText();
        }

        // --- b_reachy_engaged from secondary button (B) ---
        bool oldBReachy = bReachyPressed;
        bReachyPressed = secondaryDown;
        if (bReachyPressed != oldBReachy)
        {
            Debug.Log($"[Teleop] B_Reachy changed: {bReachyPressed}");
            UpdateOverlayText();
        }

        // --- funny_engaged from grip/side button ---
        bool oldFunny = funnyPressed;
        funnyPressed = gripDown;
        if (funnyPressed != oldFunny)
        {
            Debug.Log($"[Teleop] Funny changed: {funnyPressed}");
            UpdateOverlayText();
        }

        // --- Pose (rotation) → pitch/roll/yaw ---
        Quaternion rot;
        if (rightHand.TryGetFeatureValue(CommonUsages.deviceRotation, out rot))
        {
            Vector3 euler = rot.eulerAngles;

            // Unity convention: X = pitch, Y = yaw, Z = roll (0..360)
            float pitchRaw = NormalizeAngle(euler.x);   // now in [-180, 180]
            // Calibration: (pitch + 45) * -1
            currentPitchDeg = -(pitchRaw + 45f);

            currentYawDeg   = NormalizeAngle(euler.y);
            currentRollDeg  = NormalizeAngle(euler.z);
        }
        else
        {
            currentPitchDeg = 0f;
            currentYawDeg   = 0f;
            currentRollDeg  = 0f;
        }

        // --- Stick (primary2DAxis) in [-1, 1] ---
        Vector2 stick = Vector2.zero;
        rightHand.TryGetFeatureValue(CommonUsages.primary2DAxis, out stick);

        // Ensure components are clamped to [-1, 1]
        stick.x = Mathf.Clamp(stick.x, -1f, 1f);
        stick.y = Mathf.Clamp(stick.y, -1f, 1f);
        currentStick = stick;
    }

    // Map angle from [0, 360) to [-180, 180)
    private static float NormalizeAngle(float angleDeg)
    {
        angleDeg = Mathf.Repeat(angleDeg + 180f, 360f) - 180f;
        return angleDeg;
    }

    // =========================================================
    // Agora init / join
    // =========================================================
    private void InitAgora()
    {
        if (rtcEngine != null) return;

        rtcEngine = RtcEngine.CreateAgoraRtcEngine();

        var logConfig = new LogConfig(
            "",
            1024,
            LOG_LEVEL.LOG_LEVEL_INFO
        );

        var context = new RtcEngineContext(
            appID,
            0,
            CHANNEL_PROFILE_TYPE.CHANNEL_PROFILE_LIVE_BROADCASTING,
            "",
            AUDIO_SCENARIO_TYPE.AUDIO_SCENARIO_DEFAULT,
            AREA_CODE.AREA_CODE_GLOB,
            logConfig,
            new Optional<THREAD_PRIORITY_TYPE>(),
            false,
            false,
            false
        );

        rtcEngine.Initialize(context);
        rtcEngine.EnableVideo();
        rtcEngine.SetClientRole(CLIENT_ROLE_TYPE.CLIENT_ROLE_BROADCASTER);

        if (videoSurface == null)
        {
            Debug.LogWarning("AgoraTemiViewer: No VideoSurface assigned/found on Videoscreen.");
        }

        rtcEngine.InitEventHandler(new TemiRtcEventHandler(this, videoSurface));

        var options = new ChannelMediaOptions();
        rtcEngine.JoinChannel(token, channelName, 0, options);
    }

    public void OnJoinedChannel(RtcConnection connection, int elapsed)
    {
        joined = true;
        Debug.Log($"[AgoraTemiViewer] Joined channel {connection.channelId} as uid {connection.localUid}");
        UpdateOverlayText();
        CreateDataStream();
    }

    private void CreateDataStream()
    {
        if (rtcEngine == null || dataStreamId >= 0) return;

        var cfg = new DataStreamConfig
        {
            ordered = true,
            syncWithAudio = false
        };

        int streamId = 0;
        int ret = rtcEngine.CreateDataStream(ref streamId, cfg);
        if (ret != 0)
        {
            Debug.LogError($"[AgoraTemiViewer] CreateDataStream failed, code={ret}");
            return;
        }

        dataStreamId = streamId;
        Debug.Log($"[AgoraTemiViewer] Data stream created, id={dataStreamId}");
        UpdateOverlayText();
    }

    // =========================================================
    // Sending teleop message at ~sendRateHz
    // =========================================================
    private void SendTeleopMessage()
    {
        if (dataStreamId < 0 || rtcEngine == null)
            return;

        TeleopMessage msgObj = new TeleopMessage
        {
            number           = currentValue,
            teleop_engaged   = teleopPressed,
            a_temi_engaged   = aTemiPressed,
            b_reachy_engaged = bReachyPressed,
            funny_engaged    = funnyPressed,
            pitch_deg        = currentPitchDeg,
            roll_deg         = currentRollDeg,
            yaw_deg          = currentYawDeg,
            stick_x          = currentStick.x,
            stick_y          = currentStick.y
        };

        string json = JsonUtility.ToJson(msgObj);
        byte[] bytes = Encoding.UTF8.GetBytes(json);

        int result = rtcEngine.SendStreamMessage(dataStreamId, bytes, (uint)bytes.Length);
        if (result != 0)
        {
            Debug.LogWarning($"[AgoraTemiViewer] SendStreamMessage failed, code={result}, json={json}");
        }
        else
        {
            lastSentNumber         = msgObj.number;
            lastSentTeleopEngaged  = msgObj.teleop_engaged;
            lastSentATemiEngaged   = msgObj.a_temi_engaged;
            lastSentBReachyEngaged = msgObj.b_reachy_engaged;
            lastSentFunnyEngaged   = msgObj.funny_engaged;
            lastSentPitch          = msgObj.pitch_deg;
            lastSentRoll           = msgObj.roll_deg;
            lastSentYaw            = msgObj.yaw_deg;
            lastSentStickX         = msgObj.stick_x;
            lastSentStickY         = msgObj.stick_y;

            currentValue++; // prepare the next number
        }
    }

    private void UpdateOverlayText()
    {
        if (overlayText == null) return;

        overlayText.text =
            $"Fire: {fireStatus}\n" +
            $"Send rate: {sendRateHz:0.0} Hz (dt={sendIntervalSeconds:F4}s)\n" +
            $"Last SENT: num={lastSentNumber}, " +
            $"teleop={lastSentTeleopEngaged}, " +
            $"a_temi={lastSentATemiEngaged}, " +
            $"b_reachy={lastSentBReachyEngaged}, " +
            $"funny={lastSentFunnyEngaged}, " +
            $"pitch={lastSentPitch:F1}, roll={lastSentRoll:F1}, yaw={lastSentYaw:F1}, " +
            $"stick=({lastSentStickX:F2},{lastSentStickY:F2})\n" +
            $"Next num={currentValue}, " +
            $"local_teleop={teleopPressed}, " +
            $"local_a_temi={aTemiPressed}, " +
            $"local_b_reachy={bReachyPressed}, " +
            $"local_funny={funnyPressed}, " +
            $"local_pitch={currentPitchDeg:F1}, roll={currentRollDeg:F1}, yaw={currentYawDeg:F1}, " +
            $"local_stick=({currentStick.x:F2},{currentStick.y:F2})\n" +
            $"Last RECV: num={lastReceivedNumber}, " +
            $"teleop={lastReceivedTeleopEngaged}, " +
            $"a_temi={lastReceivedATemiEngaged}, " +
            $"b_reachy={lastReceivedBReachyEngaged}, " +
            $"funny={lastReceivedFunnyEngaged}, " +
            $"pitch={lastReceivedPitch:F1}, roll={lastReceivedRoll:F1}, yaw={lastReceivedYaw:F1}, " +
            $"stick=({lastReceivedStickX:F2},{lastReceivedStickY:F2})\n" +
            $"Raw recv: {lastReceivedRaw}";
    }

    // Called by event handler when a message arrives
    public void OnReceivedMessage(string txt, uint remoteUid)
    {
        Debug.Log($"[AgoraTemiViewer] Received from {remoteUid}: {txt}");
        lastReceivedRaw = txt;

        try
        {
            TeleopMessage parsed = JsonUtility.FromJson<TeleopMessage>(txt);
            lastReceivedNumber            = parsed.number;
            lastReceivedTeleopEngaged     = parsed.teleop_engaged;
            lastReceivedATemiEngaged      = parsed.a_temi_engaged;
            lastReceivedBReachyEngaged    = parsed.b_reachy_engaged;
            lastReceivedFunnyEngaged      = parsed.funny_engaged;
            lastReceivedPitch             = parsed.pitch_deg;
            lastReceivedRoll              = parsed.roll_deg;
            lastReceivedYaw               = parsed.yaw_deg;
            lastReceivedStickX            = parsed.stick_x;
            lastReceivedStickY            = parsed.stick_y;
        }
        catch
        {
            Debug.LogWarning("[AgoraTemiViewer] Failed to parse TeleopMessage JSON from remote.");
        }

        UpdateOverlayText();
    }

    // =========================================================
    // Inner event handler
    // =========================================================
    private class TemiRtcEventHandler : IRtcEngineEventHandler
    {
        private readonly AgoraTemiViewer owner;
        private readonly VideoSurface surface;

        public TemiRtcEventHandler(AgoraTemiViewer owner, VideoSurface surface)
        {
            this.owner = owner;
            this.surface = surface;
        }

        public override void OnJoinChannelSuccess(RtcConnection connection, int elapsed)
        {
            Debug.Log($"[TemiRtcEventHandler] Joined channel {connection.channelId} as uid {connection.localUid}");
            owner.OnJoinedChannel(connection, elapsed);
        }

        public override void OnUserJoined(RtcConnection connection, uint remoteUid, int elapsed)
        {
            Debug.Log($"[TemiRtcEventHandler] Remote user joined: {remoteUid}");

            if (surface != null)
            {
                surface.SetForUser(
                    remoteUid,
                    connection.channelId,
                    VIDEO_SOURCE_TYPE.VIDEO_SOURCE_REMOTE
                );
            }
        }

        public override void OnUserOffline(RtcConnection connection, uint remoteUid, USER_OFFLINE_REASON_TYPE reason)
        {
            Debug.Log($"[TemiRtcEventHandler] Remote user {remoteUid} left, reason: {reason}");

            if (surface != null)
            {
                surface.SetEnable(false);
            }
        }

        public override void OnStreamMessage(
            RtcConnection connection,
            uint remoteUid,
            int streamId,
            byte[] data,
            ulong length,
            ulong sentTs)
        {
            var txt = Encoding.UTF8.GetString(data, 0, (int)length);
            Debug.Log($"[TemiRtcEventHandler] OnStreamMessage from uid={remoteUid}, streamId={streamId}, len={length}, txt='{txt}'");
            owner.OnReceivedMessage(txt, remoteUid);
        }

        public override void OnStreamMessageError(
            RtcConnection connection,
            uint remoteUid,
            int streamId,
            int code,
            int missed,
            int cached)
        {
            Debug.LogError($"[TemiRtcEventHandler] OnStreamMessageError from uid={remoteUid}, streamId={streamId}, code={code}, missed={missed}, cached={cached}");
        }
    }
}
