package com.kajsiebert.sip.openai.util;

import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.message.SipResponses;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.provider.SipProviderListener;

public class OptionsListener implements SipProviderListener {

  @Override
  public void onReceivedMessage(SipProvider sip_provider, SipMessage message) {
    if (message.isRequest("OPTIONS")) {
      SipMessage response =
          sip_provider.messageFactory().createResponse(message, SipResponses.OK, null, null);
      sip_provider.sendMessage(response);
    }
  }
}
