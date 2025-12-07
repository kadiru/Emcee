using UnityEngine;
using Agora.Rtc;

public class AgoraTemiViewer : MonoBehaviour
{
    [Header("Agora")]
    [SerializeField] private string appID = "ca13c07831364faf988d67a6a15032f4";
    [SerializeField] private string channelName = "temi_hackathon";
    [SerializeField] private string token = "";   // leave empty if you are not using tokens

    // This will be the quad's VideoSurface component
    [SerializeField] private VideoSurface videoSurface;

    private IRtcEngine rtcEngine;

    private void Start()
    {
        // Auto-find VideoSurface on this GameObject or children if not assigned
        if (videoSurface == null)
        {
            videoSurface = GetComponentInChildren<VideoSurface>();
        }

        InitAgora();
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

    private void InitAgora()
    {
        if (rtcEngine != null) return;

        rtcEngine = RtcEngine.CreateAgoraRtcEngine();

        // --- LOG CONFIG (match your SDK signature: path, sizeKB, level) ---
        var logConfig = new LogConfig(
            "",                     // log file path (empty = default location)
            1024,                   // file size in KB (e.g. 1 MB)
            LOG_LEVEL.LOG_LEVEL_INFO
        );

        // --- RTC ENGINE CONTEXT (match your SDK signature) ---
        var context = new RtcEngineContext(
            appID,                                           // string appId
            0,                                               // ulong context (just 0 for default)
            CHANNEL_PROFILE_TYPE.CHANNEL_PROFILE_LIVE_BROADCASTING,
            "",                                              // string (reserved, keep empty)
            AUDIO_SCENARIO_TYPE.AUDIO_SCENARIO_DEFAULT,
            AREA_CODE.AREA_CODE_GLOB,
            logConfig,
            new Optional<THREAD_PRIORITY_TYPE>(),            // default thread priority
            false,                                           // useExternalEgl
            false,                                           // enableAudioDevice
            false                                            // enableVideoImageSource
        );

        rtcEngine.Initialize(context);
        rtcEngine.EnableVideo();
        rtcEngine.SetClientRole(CLIENT_ROLE_TYPE.CLIENT_ROLE_AUDIENCE);

        if (videoSurface == null)
        {
            Debug.LogWarning("AgoraTemiViewer: No VideoSurface assigned/found on Videoscreen.");
        }

        // Subscribe to events so we can bind remote video to our surface
        rtcEngine.InitEventHandler(new TemiRtcEventHandler(this, videoSurface));

        // --- JOIN CHANNEL ---
        // Use default ChannelMediaOptions (auto-subscribe audio/video by default)
        var options = new ChannelMediaOptions();
        rtcEngine.JoinChannel(token, channelName, 0, options);
    }

    // Simple event handler that binds the first remote user to our VideoSurface
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
            Debug.Log($"Joined channel {connection.channelId} as uid {connection.localUid}");
        }

        public override void OnUserJoined(RtcConnection connection, uint remoteUid, int elapsed)
        {
            Debug.Log($"Remote user joined: {remoteUid}");

            if (surface != null)
            {
                // Bind this surface to show that remote user's video
                surface.SetForUser(
                    remoteUid,
                    connection.channelId,
                    VIDEO_SOURCE_TYPE.VIDEO_SOURCE_REMOTE
                );
            }
        }

        public override void OnUserOffline(RtcConnection connection, uint remoteUid, USER_OFFLINE_REASON_TYPE reason)
        {
            Debug.Log($"Remote user {remoteUid} left, reason: {reason}");

            if (surface != null)
            {
                // Stop rendering when user leaves
                surface.SetEnable(false);
            }
        }
    }
}
