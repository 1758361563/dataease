package io.dataease.company.bi.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Component;

@Component
public class CompanyBiUserService {

    public static final long BOOTSTRAP_ADMIN_ID = 1L;

    private final CompanyBiUserMapper mapper;

    public CompanyBiUserService(CompanyBiUserMapper mapper) {
        this.mapper = mapper;
    }

    public CompanyBiUser findById(Long id) {
        if (id == null) {
            return null;
        }
        return mapper.selectById(id);
    }

    public CompanyBiUser findByUsername(String username) {
        if (username == null) {
            return null;
        }
        QueryWrapper<CompanyBiUser> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username);
        return mapper.selectOne(wrapper);
    }

    public long count() {
        return mapper.selectCount(null);
    }

    public void insert(CompanyBiUser user) {
        mapper.insert(user);
    }

    public void updateLastLogin(Long userId, long epochMillis) {
        UpdateWrapper<CompanyBiUser> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userId).set("last_login_at", epochMillis).set("updated_at", epochMillis);
        mapper.update(null, wrapper);
    }
}
