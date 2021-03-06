package com.cisco.core.meet;

import com.cisco.core.meet.util.SdpSsrcVariable;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.SourcePacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.BundlePacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.CandidatePacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.CandidateType;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.ContentPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.DtlsFingerprintPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.GroupPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.IceUdpTransportPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.JingleAction;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.JingleIQ;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.ParameterPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.PayloadTypePacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.RTPHdrExtPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.RtcpFbPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.RtcpmuxPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.RtpDescriptionPacketExtension;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.SSRCPacketExtension;

import org.jitsi.util.StringUtils;
import org.jivesoftware.smack.packet.IQ;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


/**
 * @author Boris Grozev
 * @author Pawel Domas
 */
public class SdpToJingle {
    static String dataType;

    private static final String RTP_SAVPF = "RTP/SAVPF";

    private static final String DTLS_SCTP = "DTLS/SCTP";

    private final static String NL = "\r\n";

    public static JingleIQ toJingle(SessionDescription sdp) {
        String[] mediaParts = sdp.description.split("m=");
        String header = mediaParts[0];
        String[] headerLines = header.split(NL);

        JingleIQ jingleIq = new JingleIQ();

        if (sdp.type == SessionDescription.Type.ANSWER) {
            jingleIq.setAction(JingleAction.SESSION_ACCEPT);
        } else if (sdp.type == SessionDescription.Type.OFFER) {
            jingleIq.setAction(JingleAction.SESSION_INITIATE);
        }

        for (int i = 1; i < mediaParts.length; i++) {
            String mediaPart = "m=" + mediaParts[i];

            ContentPacketExtension content = getContentsForMedia(mediaPart);
            jingleIq.addContent(content);
        }

        String bundleLine = lineStartingWith(headerLines, "a=group:BUNDLE");
        if (bundleLine != null) {
            String[] parts = bundleLine.split(" ");

            GroupPacketExtension groupPacketExtension = new GroupPacketExtension();

            groupPacketExtension
                    .setSemantics(GroupPacketExtension.SEMANTICS_BUNDLE);

            List<ContentPacketExtension> bundledContents = new ArrayList<>();

            for (String bundledContent : parts) {
                ContentPacketExtension content = jingleIq
                        .getContentByName(bundledContent);
                if (content != null) {
                    bundledContents.add(content);
                }
            }
            groupPacketExtension.addContents(bundledContents);

            // jingleIq.addExtension(groupPacketExtension);
        }

        return jingleIq;
    }

    private static ContentPacketExtension getContentsForMedia(String mediaPart) {
        String[] mediaLines = mediaPart.split(NL);

        String mediaType = mediaPart.substring(2, mediaPart.indexOf(" "));
        // FIXME: does not care about creator - always initiator
        ContentPacketExtension.CreatorEnum creator = ContentPacketExtension.CreatorEnum.initiator;

        String midLine = lineStartingWith(mediaLines, "a=mid:");
        String contentName = midLine.substring(6, midLine.length());

        ContentPacketExtension cpe = new ContentPacketExtension(creator,
                contentName);

        ContentPacketExtension.SendersEnum senders = ContentPacketExtension.SendersEnum.both;

        // FIXME: always responder perspective
        if (containsLine(mediaLines, "a=sendrecv")) {
            senders = ContentPacketExtension.SendersEnum.both;
        } else if (containsLine(mediaLines, "a=sendonly")) {
            senders = ContentPacketExtension.SendersEnum.responder;
        } else if (containsLine(mediaLines, "a=recvonly")) {
            senders = ContentPacketExtension.SendersEnum.initiator;
        } else if (containsLine(mediaLines, "a=inactive")) {
            senders = ContentPacketExtension.SendersEnum.none;
        }

        cpe.setSenders(senders);

        String mediaLine = lineStartingWith(mediaLines, "m=");
        String[] mediaAttributes = mediaLine.split(" ");
        String protocol = mediaAttributes[2];
        if (RTP_SAVPF.equals(protocol)) {
            // RTP/SAVPF
            // "m=audio 1 RTP/SAVPF 111 103 104 0 8 106 105 13 126"
            parseRtpSavpf2(mediaAttributes, mediaLines, cpe);
        } else if (DTLS_SCTP.equals(protocol)) {
            // DTLS/SCTP
            // "m=application 1 DTLS/SCTP 5000"
            parseDtlsSctp1(mediaAttributes, mediaLines, cpe);
        } else {
            throw new RuntimeException("Not supported protocol: " + protocol);
        }

        // Parse rtcp-mux

        return cpe;
    }

    private static void parseRtpSavpf2(String[] mediaAttributes,
                                       String[] mediaLines, ContentPacketExtension cpe) {
        // parse bundle info linpeng add
        cpe.addChildExtension(new BundlePacketExtension());

        RtpDescriptionPacketExtension rtpDescPe = new RtpDescriptionPacketExtension();

        rtpDescPe.setMedia(mediaAttributes[0].substring(2));

        dataType = mediaAttributes[0].substring(2);
        // FIXME: RtcpMux is duplicated here and in transport
        if (linesStartingWith(mediaLines, "a=rtcp-mux") != null) {
            rtpDescPe.addChildExtension(new RtcpmuxPacketExtension());
        }

        // Parse payloads
        List<String> rtpMappings = linesStartingWith(mediaLines, "a=rtpmap:");
        for (String payload : rtpMappings) {
            // a=rtpmap:111 opus/48000/2
            PayloadTypePacketExtension payloadPe = new PayloadTypePacketExtension();

            String[] parts = payload.split(" ");

            String idStr = parts[0].substring("a=rtpmap:".length());
            payloadPe.setId(Integer.parseInt(idStr));

            payloadPe.setChannels(1);

            String[] attrs = parts[1].split("/");
            payloadPe.setName(attrs[0]);
            payloadPe.setClockrate(Integer.parseInt(attrs[1]));
            if (attrs.length >= 3)
                payloadPe.setChannels(Integer.parseInt(attrs[2]));

            // Extract format parameters
            List<String> params = linesStartingWith(mediaLines, "a=fmtp:"
                    + idStr);
            for (String param : params) {
                // "a=fmtp:111 minptime=10"
                parts = param.split(" ");
                if (parts.length < 2) {
                    // FIXME: log invalid format parameter line
                    continue;
                }
                parts = parts[1].split("=");
                if (parts.length != 2) {
                    // FIXME: log invalid format parameter line
                    continue;
                }

                ParameterPacketExtension paramPe = new ParameterPacketExtension();

                paramPe.setName(parts[0]);
                paramPe.setValue(parts[1]);

                payloadPe.addParameter(paramPe);
            }

            rtpDescPe.addPayloadType(payloadPe);
        }

        // Parse RTCP feedback
        List<String> fbLines = linesStartingWith(mediaLines, "a=rtcp-fb:");
        for (String fbLine : fbLines) {
            String[] parts = fbLine.split(" ");

            String idStr = parts[0].substring("a=rtcp-fb:".length());
            int id = Integer.parseInt(idStr);

            PayloadTypePacketExtension payload = findPayload(rtpDescPe, id);
            if (payload == null) {
                // FIXME: log not found
                continue;
            }

            RtcpFbPacketExtension fbPe = new RtcpFbPacketExtension();

            fbPe.setFeedbackType(parts[1]);

            if (parts.length > 2)
                fbPe.setFeedbackSubtype(parts[2]);

            payload.addRtcpFeedbackType(fbPe);
        }

        // Parse extensions
        // a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
        List<String> extensions = linesStartingWith(mediaLines, "a=extmap:");
        for (String ext : extensions) {
            RTPHdrExtPacketExtension extPe = new RTPHdrExtPacketExtension();
            String[] parts = ext.split(" ");

            String idStr = parts[0].substring("a=extmap:".length());
            extPe.setID(idStr);

            String urn = parts[1];
            extPe.setURI(URI.create(urn));

            rtpDescPe.addExtmap(extPe);
        }

        // a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level
        // <rtp-hdrext xmlns="urn:xmpp:jingle:apps:rtp:rtp-hdrext:0" id="1"
        // uri="urn:ietf:params:rtp-hdrext:ssrc-audio-level"></rtp-hdrext>

        // <ssrc xmlns="http://estos.de/ns/ssrc"
        // label="8F9CF2C7-29A7-44E5-9438-7C0F53889DC8"
        // mslabel="34865E54-D901-4F68-A68E-7F17F31AF4E2"
        // cname="+dEz7D5ObsFDTgdK"
        // msid="34865E54-D901-4F68-A68E-7F17F31AF4E2 8F9CF2C7-29A7-44E5-9438-7C0F53889DC8"></ssrc>------------------2
        // <rtcp-mux/>
        // <rtp-hdrext xmlns="urn:xmpp:jingle:apps:rtp:rtp-hdrext:0"
        // uri="urn:ietf:params:rtp-hdrext:ssrc-audio-level"
        // id="1"></rtp-hdrext>
        //
        // parse ssrc linpeng add

        // RTPHdrExtPacketExtension ssrcPeadd = new RTPHdrExtPacketExtension();
        // rtpDescPe.addExtmap(ssrcPeadd);
        // MediaPresenceExtension ssrcPe1 = new MediaPresenceExtension();
        // ssrcPe1.set
        //
        // rtpDescPe.addChildExtension(childExtension);

        // Parse SSRCs
        List<String> ssrcLines = linesStartingWith(mediaLines, "a=ssrc:");
        Map<String, SourcePacketExtension> ssrcMap = new HashMap<>();
        SSRCPacketExtension ssrcpe = new SSRCPacketExtension();
        String cname = null, msid = null, mslabel = null, label = null;
        for (String ssrcLine : ssrcLines) {
            int spaceIdx = ssrcLine.indexOf(" ");
            String ssrc = ssrcLine.substring("a=ssrc:".length(), spaceIdx);

            SourcePacketExtension ssrcPe = ssrcMap.get(ssrc);
            if (ssrcPe == null) {
                ssrcPe = new SourcePacketExtension();

                ssrcPe.setSSRC(Long.valueOf(ssrc));
                System.out.println("---------ssrc=-" + ssrc);
                rtpDescPe.setSsrc(ssrc);// linpeng add

                rtpDescPe.addChildExtension(ssrcPe);

                ssrcMap.put(ssrc, ssrcPe);
            }

            String[] paramParts = ssrcLine.substring(spaceIdx + 1).split(":");
            ParameterPacketExtension param = new ParameterPacketExtension();
            param.setName(paramParts[0]);
            param.setValue(paramParts[1]);

            ssrcPe.addParameter(param);

            // linpeng add
            if (paramParts[0].equals("cname")) {
                cname = paramParts[1];
            } else if (paramParts[0].equals("msid")) {
                msid = paramParts[1];
            } else if (paramParts[0].equals("mslabel")) {
                mslabel = paramParts[1];
            } else if (paramParts[0].equals("label")) {
                label = paramParts[1];
            }
        }

        // linpeng add ssrc
        ssrcpe.setLabel(label);
        ssrcpe.setCname(cname);
        ssrcpe.setMsid(msid);
        ssrcpe.setMslabel(mslabel);
        rtpDescPe.addChildExtension(ssrcpe);

        cpe.addChildExtension(rtpDescPe);

        // parse ssrc linpengadd
        if (dataType.equals("audio")) {
            SdpSsrcVariable.ice_cd1 = rtpDescPe.getSsrc();
            System.out.println("---------ssrc=ice_cd1-" + SdpSsrcVariable.ice_cd1);
        } else if (dataType.equals("video")) {
            SdpSsrcVariable.ice_cd2 = rtpDescPe.getSsrc();
            System.out.println("---------ssrc=ice_cd2-" + SdpSsrcVariable.ice_cd2);
        }

        // parse transport info
        IceUdpTransportPacketExtension icePe = parseTransportInfo(mediaLines);
        cpe.addChildExtension(icePe);

    }

    private static IceUdpTransportPacketExtension parseTransportInfo(
            String[] mediaLines) {
        SdpSsrcVariable.mediaLines = mediaLines;
        IceUdpTransportPacketExtension icePe = new IceUdpTransportPacketExtension();
        DtlsFingerprintPacketExtension dtlsPe = new DtlsFingerprintPacketExtension();

        String uFrag = lineStartingWith(mediaLines, "a=ice-ufrag:");
        if (!StringUtils.isNullOrEmpty(uFrag)) {
            icePe.setUfrag(uFrag.substring("a=ice-ufrag:".length()));
        }

        String pwd = lineStartingWith(mediaLines, "a=ice-pwd:");
        if (!StringUtils.isNullOrEmpty(pwd)) {
            icePe.setPassword(pwd.substring("a=ice-pwd:".length()));
        }

        String fpLine = lineStartingWith(mediaLines, "a=fingerprint:");
        if (!StringUtils.isNullOrEmpty(fpLine)) {
            String[] parts = fpLine.split(" ");
            String hash = parts[0].substring("a=fingerprint:".length());
            String fingerprint = parts[1];
            dtlsPe.setHash(hash);
            dtlsPe.setFingerprint(fingerprint);
            icePe.addChildExtension(dtlsPe);
        }
        if (dataType.equals("audio")) {

            if (SdpSsrcVariable.ice_candidate != null) {
                for (int i = 0; i < SdpSsrcVariable.ice_candidate.size(); i++) {
                    CandidatePacketExtension candidatePe = SdpToJingle
                            .parseCandidate(SdpSsrcVariable.ice_candidate.get(i).sdp);
                    icePe.addChildExtension(candidatePe);
                }
            }
        } else if (dataType.equals("video")) {

        }
        // FIXME: parse setup, but no attribute for that yet ?
        // if (line.startsWith("setup:"))

        // Parse candidates
        List<String> candidates = linesStartingWith(mediaLines, "a=candidate:");

        for (String candidate : candidates) {
            CandidatePacketExtension candidatePe = SdpToJingle
                    .parseCandidate(candidate);

            icePe.addCandidate(candidatePe);
        }

        // if (lineStartingWith(mediaLines, "a=rtcp-mux") != null)
        // {
        // icePe.addChildExtension(new RtcpmuxPacketExtension());
        // }

        return icePe;
    }

    private static PayloadTypePacketExtension findPayload(
            RtpDescriptionPacketExtension rtpDesc, int id) {
        for (PayloadTypePacketExtension pe : rtpDesc.getPayloadTypes()) {
            if (id == pe.getID())
                return pe;
        }
        return null;
    }

    private static void parseDtlsSctp1(String[] mediaAttributes,
                                       String[] mediaLines, ContentPacketExtension cpe) {

        cpe.addChildExtension(new BundlePacketExtension());

        RtpDescriptionPacketExtension rtpDescPe = new RtpDescriptionPacketExtension();

        // a=mid:data
        // rtpDescPe.setMedia(mediaAttributes[0].substring(2));//linpeng update
        rtpDescPe.setMedia("data");
        cpe.addChildExtension(rtpDescPe);

        // // FIXME: RtcpMux is duplicated here and in transport linpeng
        // if (linesStartingWith(mediaLines, "a=rtcp-mux") != null)
        // {
        // rtpDescPe.addChildExtension(new RtcpmuxPacketExtension());
        // }
        //
        // parse bundle info linpeng add

        // IceUdpTransportPacketExtension icePe =
        // parseTransportInfo(mediaLines);
        IceUdpTransportPacketExtension icePe = new IceUdpTransportPacketExtension();
        String uFrag = lineStartingWith(mediaLines, "a=ice-ufrag:");
        if (!StringUtils.isNullOrEmpty(uFrag)) {
            icePe.setUfrag(uFrag.substring("a=ice-ufrag:".length()));
        }

        String pwd = lineStartingWith(mediaLines, "a=ice-pwd:");
        if (!StringUtils.isNullOrEmpty(pwd)) {
            icePe.setPassword(pwd.substring("a=ice-pwd:".length()));
        }
        cpe.addChildExtension(icePe);

        DtlsFingerprintPacketExtension dtlsPe = new DtlsFingerprintPacketExtension();
        String fpLine = lineStartingWith(mediaLines, "a=fingerprint:");
        if (!StringUtils.isNullOrEmpty(fpLine)) {
            String[] parts = fpLine.split(" ");
            String hash = parts[0].substring("a=fingerprint:".length());
            String fingerprint = parts[1];
            dtlsPe.setHash(hash);
            dtlsPe.setFingerprint(fingerprint);
            icePe.addChildExtension(dtlsPe);
        }

        // a=sctpmap:5000 webrtc-datachannel 1024

        // String sctpMap = lineStartingWith(mediaLines, "a=sctpmap:");
        // if (!StringUtils.isNullOrEmpty(sctpMap))
        // {
        // SctpMapExtension sctpMapPe = new SctpMapExtension();
        //
        // String[] parts = sctpMap.split(" ");
        //
        // String sctpPortStr = parts[0].substring("a=sctpmap:".length());
        // int sctpPort = Integer.parseInt(sctpPortStr);
        //
        // sctpMapPe.setPort(sctpPort);
        //
        // sctpMapPe.setProtocol(parts[1]);
        //
        // sctpMapPe.setStreams(Integer.valueOf(parts[2]));
        //
        // rtpDescPe.addChildExtension(sctpMapPe);
        // }
        // cpe.addChildExtension(rtpDescPe);
    }

    private static String lineStartingWith(String[] lines, String start) {
        for (String line : lines) {
            if (line.startsWith(start))
                return line;
        }
        return null;
    }

    private static List<String> linesStartingWith(String[] lines, String start) {
        LinkedList<String> result = new LinkedList<>();
        for (String line : lines) {
            if (line.startsWith(start))
                result.add(line);
        }
        return result;
    }

    private static boolean containsLine(String[] lines, String line) {
        for (String l : lines) {
            if (l.equals(line))
                return true;
        }
        return false;
    }

    public static JingleIQ createTransportInfo(String jid,
                                               IceCandidate candidate) {
        JingleIQ iq = new JingleIQ();
        iq.setAction(JingleAction.TRANSPORT_INFO);
        iq.setTo(jid);
        iq.setType(IQ.Type.SET);

        ContentPacketExtension content = new ContentPacketExtension(
                ContentPacketExtension.CreatorEnum.initiator, candidate.sdpMid);
        IceUdpTransportPacketExtension transport = new IceUdpTransportPacketExtension();
        CandidatePacketExtension cpe = parseCandidate(candidate.sdp);
        transport.addCandidate(cpe);
        content.addChildExtension(transport);
        iq.addContent(content);

        return iq;
    }

    public static CandidatePacketExtension parseCandidate(String c) {
        CandidatePacketExtension cpe = new CandidatePacketExtension();

//        System.out.println("===================c==" + c);
        // a=candidate:2 1 udp 2130706431 176.31.40.85 10031 typ host generation
        // 0
        // a=candidate:3 1 ssltcp 2113939711 2001:41d0:d:750:0:0:0:9 4443 typ
        // host generation 0
        // video:1:candidate:3055564277 1 udp 2122260223 192.168.3.130 45645 typ
        // host generation 0
        // candidate:3055564277 1 udp 2122260223 192.168.3.130 46246 typ host
        // generation 0

        // ===================c==candidate:1226721975 1 udp 2122260223
        // 192.168.3.165 50078 typ host generation 0 ufrag FZ3g network-id 3
        // network-cost 10

        // String[] parts = c.substring(12).split(" ");
//		       a=candidate: candidate.getFoundation()    candidate.getComponent()  candidate.getProtocol() candidate.getPriority() candidate.getIP() candidate.getPort() candidate.getType() candidate.getGeneration()

//		audio:0:candidate:3924879826 1 udp 2122260223 192.168.4.103 59011 typ host generation 0 ufrag 4n0c network-id 3 network-cost 10
//		audio:0:candidate:1756950374 1 udp 1686052607 218.241.219.88 9920 typ srflx raddr 192.168.4.103 rport 59011 generation 0 ufrag 4n0c network-id 3 network-cost 10

//		sb.append("a=candidate:").append(candidate.getFoundation()).append(' ').append(candidate.getComponent());
//		sb.append(' ').append(candidate.getProtocol()).append(' ').append(candidate.getPriority());
//		sb.append(' ').append(candidate.getIP()).append(' ').append(candidate.getPort()).append(" typ ");
//		sb.append(candidate.getType().toString()).append(" generation ").append(candidate.getGeneration());
//		sb.append(NL);
        String[] parts = c.substring(c.lastIndexOf("candidate:")+1).split(" ");
        String foundation = parts[0];
        String component = parts[1];
        String protocol = parts[2];
        String priority = parts[3];
        String addr = parts[4];
        String port = parts[5];
        String typ = parts[7];

        cpe.setPort(Integer.valueOf(port));
        cpe.setFoundation(foundation);
        cpe.setProtocol(protocol);
        cpe.setPriority(Long.valueOf(priority));
        cpe.setComponent(Integer.valueOf(component));
        cpe.setIP(addr);
        cpe.setNetwork(1);// linpeng add
        cpe.setID(SdpSsrcVariable.getUUID());
        cpe.setType(CandidateType.valueOf(typ));

        String generation = null;
        String raddr = null;
        String rport = null;
        String ufrag = null;
        String networkId = null;
        String networkCost = null;

        if ("host".equals(typ)) {
            generation = parts[9];
            ufrag = parts[11];
            networkId = parts[13];
            if(parts.length>14){
                networkCost = parts[15];
            }

        } else {
            raddr = parts[9];
            rport = parts[11];
            generation = parts[13];
            ufrag = parts[15];
            networkId = parts[17];
            networkCost = parts[19];
            cpe.setRelAddr(raddr);
            cpe.setRelPort(Integer.valueOf(rport));
        }


        cpe.setGeneration(Integer.valueOf(generation));
        return cpe;
    }

    public static void main(String[] args) {
        String string = "audio:0:candidate:3924879826 1 udp 2122260223 192.168.4.103 59011 typ host generation 0 ufrag 4n0c network-id 3 network-cost 10";
        CandidatePacketExtension c = SdpToJingle.parseCandidate(string);
        System.out.println(c.toXML());
        string = "audio:0:candidate:1756950374 1 udp 1686052607 218.241.219.88 9920 typ srflx raddr 192.168.4.103 rport 59011 generation 0 ufrag 4n0c network-id 3 network-cost 10";
        c = SdpToJingle.parseCandidate(string);
        System.out.println(c.toXML());

        String a="candidate:559267639 1 udp 2122202367 ::1 38601 typ host generation 0 ufrag hq7d network-id 2";

        CandidatePacketExtension c2 = SdpToJingle.parseCandidate(a);
        System.out.println("2="+c2.toXML());

    }
}
