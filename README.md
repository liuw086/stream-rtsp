借鉴如下工程，做设备录屏使用rtsp协议推流到本地1935端口
- https://github.com/pedroSG94/rtmp-rtsp-stream-client-java 
- https://github.com/pedroSG94/RTSP-Server

可以使用ffplay 进行拉流播放
ffplay rtsp://10.100.6.1:1935/
