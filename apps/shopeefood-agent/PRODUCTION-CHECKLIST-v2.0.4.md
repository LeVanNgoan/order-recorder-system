# Production checklist v2.0.4

- [ ] `applicationId = vn.orderrecorder.shopee`
- [ ] `versionCode = 22`
- [ ] `versionName = 2.0.3`
- [ ] Same signing keystore as v2.0.1/v2.0.0
- [ ] Automation service + notification listener use v0.2.5 core flow
- [ ] NodeUtil is byte-identical to v0.2.5
- [ ] No accessibility overlay / touch blocker
- [ ] No rapid-scan worker / user-idle arbitration in live core
- [ ] FIFO: one order finishes before next order starts
- [ ] Per-order sound grace = 1000 ms
- [ ] Contact binding requires correct active order
- [ ] Only `Khách nhận đơn` arms phone capture
- [ ] Phone capture window restored to 15 seconds; success exits immediately
- [ ] Direct-in-Shopee phone + Dialer fallback both supported
- [ ] Local save happens before Hub sync
- [ ] `SPF-xxxx` + VN phone normalization retained
- [ ] Hub force-resync 7 days retained
- [ ] On package replacement, volatile old automation queue/state is cleared; business data is preserved
