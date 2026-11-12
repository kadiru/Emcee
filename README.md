# Emcee — Reachy Reach

**Robotics and XR integration · SensAI Hack 2025, San Francisco**

We prototyped an event host with two robots: a mobile temi to approach guests and a Reachy Mini to add expression. The idea was for a person in XR to see the room from the robot’s point of view and help guide how the pair interacted with guests.

[Watch the team demo and read the SensAI submission](https://devpost.com/software/700-sf11-vamos-drj9lt)

![Emcee integration: robot camera video into the Unity XR view, and Quest controller state through Agora to a Python listener.](docs/emcee-robotics-xr.svg)

## What I built

I worked on the robotics and XR integration. My focus was getting the operator a view of the robot’s surroundings and sending the operator’s input back out of the headset.

| Piece | What it does | Code |
| --- | --- | --- |
| **temi camera app** | Previews the camera on temi and serves camera video and audio over RTSP. | [Android / Kotlin](android/app/src/main/java/com/example/temihelloworld/MainActivity.kt) |
| **XR video view** | Receives a remote Agora video stream and renders it on a surface in the Unity scene. | [Unity / C#](unityWin/EmceeXR/Assets/AgoraTemiViewer.cs) |
| **Quest 3 input bridge** | Reads the right controller’s buttons, thumbstick and rotation, then publishes the controller state as JSON on an Agora RTC data stream. | [Unity / OpenXR](unityWin/EmceeXR/Assets/AgoraTemiViewer.cs) |
| **Reachy-side receiver** | Joins the Agora channel and logs incoming data messages so we could check that the controller input arrived. | [Python listener](pythonReachy/reachy_teleop_rtc.py) |

### What the controller sends

| Input | State in the message |
| --- | --- |
| Trigger | Teleoperation engaged |
| A / B | temi / Reachy flags |
| Grip | Playful interaction flag (`funny_engaged`) |
| Thumbstick and rotation | Stick X/Y and controller pitch, roll, and yaw |

## Prototype scope

The checked-in Android app serves RTSP; the Unity viewer subscribes to Agora RTC video. The relay between those two pieces is outside this repository. The Python receiver logs inputs and does not drive Reachy Mini’s motors. The [team submission](https://devpost.com/software/700-sf11-vamos-drj9lt) shows the larger event-host prototype and describes the team’s interaction and language model work.

**Team:** Kadir Uyanik (robotics and XR), Varun Bhargava (language models and scripting ideas), and Ryota (XR, language models, and ideation).
