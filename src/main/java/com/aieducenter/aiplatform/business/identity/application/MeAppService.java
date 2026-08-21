package com.aieducenter.aiplatform.business.identity.application;

import org.springframework.stereotype.Service;

import com.cartisan.core.context.RequestContext;

import com.aieducenter.aiplatform.business.identity.application.dto.response.MeResponse;

/**
 * 当前账号查询（A2 §3：/api/me 纯读）。claims 已在 callback 建档时映射进会话，
 * 此处不查库——拦截器保证了 /api/** 必有用户上下文（userId=accountId、userName=显示名）。
 */
@Service
public class MeAppService {

    public MeResponse currentAccount() {
        return new MeResponse(
                String.valueOf(RequestContext.getUserId()),
                RequestContext.getUserName());
    }
}
