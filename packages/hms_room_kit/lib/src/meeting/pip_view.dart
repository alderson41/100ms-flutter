import 'package:flutter/material.dart';
import 'package:hmssdk_flutter/hmssdk_flutter.dart';
import 'package:hms_room_kit/src/model/peer_track_node.dart';
import 'package:hms_room_kit/src/meeting/meeting_store.dart';
import 'package:provider/provider.dart';

/// Call-only presentation; never render the patient's own tile or room chrome.
class PipView extends StatelessWidget {
  const PipView({super.key});

  @override
  Widget build(BuildContext context) {
    final meeting = context.watch<MeetingStore>();
    final clinicians = meeting.peerTracks
        .where((node) =>
            !node.peer.isLocal &&
            ((node.peer.customerUserId?.endsWith('_practitioner') ?? false) ||
                node.peer.role.name == 'host'))
        .toList()
      ..sort((a, b) => a.uid.compareTo(b.uid));
    PeerTrackNode? selected;
    for (final node in clinicians) {
      if (node.track?.source.toUpperCase() == 'SCREEN' &&
          node.track?.isMute == false) {
        selected = node;
        break;
      }
    }
    for (final node in clinicians) {
      if (selected == null && node.track?.source.toUpperCase() == 'REGULAR') {
        selected = node;
      }
    }
    final selectedNode = selected;
    return Scaffold(
      backgroundColor: Colors.black,
      body: selectedNode == null
          ? const _Placeholder('Waiting for clinician')
          : ListenableBuilder(
              listenable: selectedNode,
              builder: (context, _) {
                final track = selectedNode.track;
                if (track == null || track.isMute || track.isDegraded == true) {
                  return _Placeholder(meeting.reconnecting
                      ? 'Reconnecting…'
                      : 'Clinician camera is off');
                }
                return SizedBox.expand(
                  child: HMSTextureView(
                    key: ValueKey('pip-${track.trackId}'),
                    track: track,
                    scaleType: track.source.toUpperCase() == 'SCREEN'
                        ? ScaleType.SCALE_ASPECT_FIT
                        : ScaleType.SCALE_ASPECT_FILL,
                    setMirror: false,
                  ),
                );
              },
            ),
    );
  }
}

class _Placeholder extends StatelessWidget {
  final String message;
  const _Placeholder(this.message);

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: Text(message,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white, fontSize: 12)),
        ),
      );
}
