import 'package:better_player/better_player.dart';
import 'package:better_player_example/constants.dart';
import 'package:better_player_example/utils.dart';
import 'package:flutter/material.dart';

class BasicPlayerPage extends StatefulWidget {
  @override
  _BasicPlayerPageState createState() => _BasicPlayerPageState();
}

class _BasicPlayerPageState extends State<BasicPlayerPage> {
  String adsUrl = "https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_preroll_skippable&sz=640x480&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&impl=s&correlator=";
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text("Basic player"),
      ),
      body: Column(
        children: [
          const SizedBox(height: 8),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Text(
              "Basic player created with the simplest factory method. Shows video from URL.",
              style: TextStyle(fontSize: 16),
            ),
          ),
          BetterPlayer.network(
            Constants.forBiggerBlazesUrl,
            adsUrl: adsUrl,
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Text(
              "Next player shows video from file.",
              style: TextStyle(fontSize: 16),
            ),
          ),
          const SizedBox(height: 8),
          FutureBuilder<String>(
            future: Utils.getFileUrl(Constants.fileTestVideoUrl),
            builder: (BuildContext context, AsyncSnapshot<String> snapshot) {
              if (snapshot.data != null) {
                return BetterPlayer.file(snapshot.data!, adsUrl: adsUrl,);
              } else {
                return const SizedBox();
              }
            },
          )
        ],
      ),
    );
  }
}
