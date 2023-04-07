import 'package:better_player/better_player.dart';
import 'package:better_player/src/video_player/video_player.dart';

import 'better_player_mock_controller.dart';
import 'mock_video_player_controller.dart';

class BetterPlayerTestUtils {
  static const String bugBuckBunnyVideoUrl =
      "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4";
  static const String forBiggerBlazesUrl =
      "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4";
  static const String elephantDreamStreamUrl =
      "http://cdn.theoplayer.com/video/elephants-dream/playlist.m3u8";

  static BetterPlayerMockController setupBetterPlayerMockController(
      {VideoPlayerController? controller}) {
    final mockController =
        BetterPlayerMockController(const BetterPlayerConfiguration());
    if (controller != null) {
      mockController.videoPlayerController = controller;
    }
    return mockController;
  }

  static MockVideoPlayerController setupMockVideoPlayerControler() {
    final mockVideoPlayerController = MockVideoPlayerController();
    mockVideoPlayerController
        .setNetworkDataSource(BetterPlayerTestUtils.forBiggerBlazesUrl, adsSource: "https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_preroll_skippable&sz=640x480&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&impl=s&correlator=");
    return mockVideoPlayerController;
  }
}
