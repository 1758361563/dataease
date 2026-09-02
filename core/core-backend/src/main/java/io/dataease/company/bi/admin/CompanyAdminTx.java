package io.dataease.company.bi.admin;

import java.util.function.Supplier;

public interface CompanyAdminTx {

    <T> T execute(Supplier<T> work);
}
