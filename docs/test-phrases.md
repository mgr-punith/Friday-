# Friday command evaluation phrases

`npm run eval` reads this table. Keep arguments aligned with [actions.md](actions.md). The fixed test time is 17 November 2026 at 9:30 AM in India. Indian English rows cover phrasing and names; use the private WAV evaluation in [testing.md](testing.md) to measure accent recognition.

| Phrase | Action | Args |
|---|---|---|
| `Call Mom` | `call_contact` | `{"name":"Mom"}` |
| `Phone Arjun` | `call_contact` | `{"name":"Arjun"}` |
| `Ring Dr Shah` | `call_contact` | `{"name":"Dr Shah"}` |
| `Call my brother` | `call_contact` | `{"name":"my brother"}` |
| `Call Rebecca` | `call_contact` | `{"name":"Rebecca"}` |
| `Call Punith` | `call_contact` | `{"name":"Punith"}` |
| `Call Puneet` | `call_contact` | `{"name":"Puneet"}` |
| `Phone Pradeep` | `call_contact` | `{"name":"Pradeep"}` |
| `Call Suresh Kumar` | `call_contact` | `{"name":"Suresh Kumar"}` |
| `Ring Ananya` | `call_contact` | `{"name":"Ananya"}` |
| `Call Doctor Reddy` | `call_contact` | `{"name":"Doctor Reddy"}` |
| `Call plus nine one nine eight seven six five four three two one zero` | `call_number` | `{"number":"+91 9876543210"}` |
| `Dial 1800 555 0199` | `call_number` | `{"number":"18005550199"}` |
| `Call 98765 43210` | `call_number` | `{"number":"9876543210"}` |
| `Dial double nine eight zero one two three four five six` | `call_number` | `{"number":"9980123456"}` |
| `Call triple nine zero one two three four five six` | `call_number` | `{"number":"9990123456"}` |
| `Set an alarm for 6 30 AM` | `set_alarm` | `{"hour":6,"minute":30}` |
| `Put one alarm for six thirty in the morning` | `set_alarm` | `{"hour":6,"minute":30}` |
| `Keep an alarm for tomorrow morning at seven` | `set_alarm` | `{"hour":7,"minute":0}` |
| `Wake me at 7 tomorrow morning` | `set_alarm` | `{"hour":7,"minute":0}` |
| `Set an alarm for 10 45 tonight` | `set_alarm` | `{"hour":22,"minute":45}` |
| `Set a gym alarm for 5 AM` | `set_alarm` | `{"hour":5,"minute":0,"label":"gym"}` |
| `Alarm at noon` | `set_alarm` | `{"hour":12,"minute":0}` |
| `Wake me at midnight` | `set_alarm` | `{"hour":0,"minute":0}` |
| `Set a timer for 20 minutes` | `set_timer` | `{"seconds":1200}` |
| `Timer for 45 seconds` | `set_timer` | `{"seconds":45}` |
| `Start a two hour timer` | `set_timer` | `{"seconds":7200}` |
| `Remind me in 10 minutes` | `set_timer` | `{"seconds":600}` |
| `Count down five minutes` | `set_timer` | `{"seconds":300}` |
| `Give me a one minute timer` | `set_timer` | `{"seconds":60}` |
| `Keep a timer for five minutes` | `set_timer` | `{"seconds":300}` |
| `Put a timer of two minutes` | `set_timer` | `{"seconds":120}` |
| `Open Camera` | `open_app` | `{"name":"Camera"}` |
| `Launch WhatsApp` | `open_app` | `{"name":"WhatsApp"}` |
| `Open PhonePe` | `open_app` | `{"name":"PhonePe"}` |
| `Open JioSaavn` | `open_app` | `{"name":"JioSaavn"}` |
| `Start Spotify` | `open_app` | `{"name":"Spotify"}` |
| `Open my calculator` | `open_app` | `{"name":"calculator"}` |
| `Go to YouTube` | `open_app` | `{"name":"YouTube"}` |
| `Open Maps` | `open_app` | `{"name":"Maps"}` |
| `Close this app` | `close_app` | `{}` |
| `Go home` | `close_app` | `{}` |
| `Exit Spotify` | `close_app` | `{"name":"Spotify"}` |
| `Leave this screen` | `close_app` | `{}` |
| `Put my phone on silent` | `set_ringer_mode` | `{"mode":"silent"}` |
| `Switch to vibrate` | `set_ringer_mode` | `{"mode":"vibrate"}` |
| `Make it vibrate` | `set_ringer_mode` | `{"mode":"vibrate"}` |
| `Keep the phone on ring mode` | `set_ringer_mode` | `{"mode":"normal"}` |
| `Turn the ringer back on` | `set_ringer_mode` | `{"mode":"normal"}` |
| `Normal sound mode` | `set_ringer_mode` | `{"mode":"normal"}` |
| `Mute my phone` | `set_ringer_mode` | `{"mode":"silent"}` |
| `What is the weather today` | `unsupported` | `{}` |
| `Who is the prime minister` | `unsupported` | `{}` |
| `Tell me a joke` | `unsupported` | `{}` |
| `Search the web for coffee` | `unsupported` | `{}` |
| `Send a message to Mom` | `unsupported` | `{}` |
| `Turn on Wi-Fi` | `unsupported` | `{}` |
| `Enable Bluetooth` | `unsupported` | `{}` |
| `Take a photo` | `unsupported` | `{}` |
| `Read my notifications` | `unsupported` | `{}` |
| `Play some music` | `unsupported` | `{}` |
| `Book a cab` | `unsupported` | `{}` |
| `Translate hello to Hindi` | `unsupported` | `{}` |
| `How are you` | `unsupported` | `{}` |
| `Write an email` | `unsupported` | `{}` |
| `Increase the brightness` | `unsupported` | `{}` |
