package net.bither.db;

import net.bither.ApplicationInstanceManager;
import net.bither.bitherj.core.Address;
import net.bither.bitherj.core.HDMAddress;
import net.bither.bitherj.core.HDMBId;
import net.bither.bitherj.core.HDMKeychain;
import net.bither.bitherj.crypto.EncryptedData;
import net.bither.bitherj.crypto.PasswordSeed;
import net.bither.bitherj.db.AbstractDb;
import net.bither.bitherj.db.IAddressProvider;
import net.bither.bitherj.exception.AddressFormatException;
import net.bither.bitherj.utils.Base58;
import net.bither.bitherj.utils.Utils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AddressProvider implements IAddressProvider {

    private static AddressProvider addressProvider =
            new AddressProvider(ApplicationInstanceManager.addressDatabaseHelper);

    private static final String insertHDSeedSql = "insert into hd_seeds " +
            "(encrypt_seed,encrypt_HD_seed,is_xrandom,hdm_address)" +
            " values (?,?,?,?) ";


    private static final String insertAddressSql = "insert into addresses " +
            "(address,encrypt_private_key,pub_key,is_xrandom,is_trash,is_synced,sort_time)" +
            " values (?,?,?,?,?,?,?) ";

    private static final String insertHDMBidSql = "insert into hdm_bid " +
            "(hdm_bid,encrypt_bither_password)" +
            " values (?,?) ";

    public static AddressProvider getInstance() {
        return addressProvider;
    }

    private AddressDatabaseHelper mDb;


    private AddressProvider(AddressDatabaseHelper db) {
        this.mDb = db;
    }

    @Override
    public boolean changePassword(CharSequence oldPassword, CharSequence newPassword) {
        final HashMap<String, String> addressesPrivKeyHashMap = new HashMap<String, String>();
        String hdmEncryptPassword = null;
        PasswordSeed passwordSeed = null;
        final HashMap<Integer, String> encryptSeedHashMap = new HashMap<Integer, String>();
        final HashMap<Integer, String> encryptHDSeedHashMap = new HashMap<Integer, String>();

        try {
            String sql = "select address,encrypt_private_key from addresses where encrypt_private_key is not null";
            ResultSet c = this.mDb.query(sql, null);
            while (c.next()) {
                String address = null;
                String encryptPrivKey = null;
                int idColumn = c.findColumn(AbstractDb.AddressesColumns.ADDRESS);
                if (idColumn != -1) {
                    address = c.getString(idColumn);
                }
                idColumn = c.findColumn(AbstractDb.AddressesColumns.ENCRYPT_PRIVATE_KEY);
                if (idColumn != -1) {
                    encryptPrivKey = c.getString(idColumn);
                }
                addressesPrivKeyHashMap.put(address, encryptPrivKey);
            }
            c.close();
            sql = "select encrypt_bither_password from hdm_bid limit 1";
            c = this.mDb.query(sql, null);
            if (c.next()) {

                hdmEncryptPassword = c.getString(0);
            } else {
                hdmEncryptPassword = null;
            }
            c.close();
            sql = "select hd_seed_id,encrypt_seed,encrypt_hd_seed from hd_seeds where encrypt_seed!='RECOVER'";
            c = this.mDb.query(sql, null);
            while (c.next()) {
                int idColumn = c.findColumn(AbstractDb.HDSeedsColumns.HD_SEED_ID);
                Integer hdSeedId = 0;
                if (idColumn != -1) {
                    hdSeedId = c.getInt(idColumn);
                }

                String encryptSeed = null;
                idColumn = c.findColumn(AbstractDb.HDSeedsColumns.ENCRYPT_SEED);
                if (idColumn != -1) {
                    encryptSeed = c.getString(idColumn);
                }
                idColumn = c.findColumn(AbstractDb.HDSeedsColumns.ENCRYPT_HD_SEED);
                if (idColumn != -1) {
                    String encryptHDSeed = c.getString(idColumn);
                    if (!Utils.isEmpty(encryptHDSeed)) {
                        encryptHDSeedHashMap.put(hdSeedId, encryptHDSeed);
                    }
                }
                encryptSeedHashMap.put(hdSeedId, encryptSeed);
            }
            c.close();
            sql = "select password_seed from password_seed limit 1";
            c = this.mDb.query(sql, null);
            if (c.next()) {
                int idColumn = c.findColumn(AbstractDb.PasswordSeedColumns.PASSWORD_SEED);
                if (idColumn != -1) {
                    passwordSeed = new PasswordSeed(c.getString(idColumn));
                }
            }
            c.close();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }

        for (Map.Entry<String, String> kv : addressesPrivKeyHashMap.entrySet()) {
            kv.setValue(EncryptedData.changePwd(kv.getValue(), oldPassword, newPassword));
        }
        if (hdmEncryptPassword != null) {
            hdmEncryptPassword = EncryptedData.changePwd(hdmEncryptPassword, oldPassword, newPassword);
        }
        for (Map.Entry<Integer, String> kv : encryptSeedHashMap.entrySet()) {
            kv.setValue(EncryptedData.changePwd(kv.getValue(), oldPassword, newPassword));
        }
        for (Map.Entry<Integer, String> kv : encryptHDSeedHashMap.entrySet()) {
            kv.setValue(EncryptedData.changePwd(kv.getValue(), oldPassword, newPassword));
        }
        if (passwordSeed != null) {
            boolean result = passwordSeed.changePassword(oldPassword, newPassword);
            if (!result) {
                return false;
            }
        }
        final String finalHdmEncryptPassword = hdmEncryptPassword;
        final PasswordSeed finalPasswordSeed = passwordSeed;
        try {

            this.mDb.getConn().setAutoCommit(false);
            String sql = "update addresses set encrypt_private_key=? where  address=? ";
            for (Map.Entry<String, String> kv : addressesPrivKeyHashMap.entrySet()) {
                PreparedStatement stmt = this.mDb.getConn().prepareStatement(sql);
                stmt.setString(1, kv.getValue());
                stmt.setString(2, kv.getKey());
                stmt.executeUpdate();
            }
            sql = "update hdm_bid set encrypt_bither_password=?  ";
            if (finalHdmEncryptPassword != null) {
                PreparedStatement stmt = this.mDb.getConn().prepareStatement(sql);
                stmt.setString(1, finalHdmEncryptPassword);
                stmt.executeUpdate();

            }
            sql = "update hd_seeds set encrypt_seed=? %s where  hd_seed_id=? ";
            for (Map.Entry<Integer, String> kv : encryptSeedHashMap.entrySet()) {
                if (encryptHDSeedHashMap.containsKey(kv.getKey())) {
                    sql = Utils.format(sql, ",encrypt_HD_seed=" + encryptHDSeedHashMap.get(kv.getKey()));
                }
                PreparedStatement stmt = this.mDb.getConn().prepareStatement(sql);
                stmt.setString(1, kv.getValue());
                stmt.setString(2, kv.getKey().toString());
                stmt.executeUpdate();
            }
            if (finalPasswordSeed != null) {
                sql = "update password_seed set password_seed=?  ";
                PreparedStatement stmt = this.mDb.getConn().prepareStatement(sql);
                stmt.setString(1, finalPasswordSeed.toPasswordSeedString());
                stmt.executeUpdate();
            }
            this.mDb.getConn().commit();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    @Override
    public PasswordSeed getPasswordSeed() {
        ResultSet c = this.mDb.query("select password_seed from password_seed limit 1", null);
        PasswordSeed passwordSeed = null;
        try {
            if (c.next()) {
                int idColumn = c.findColumn(AbstractDb.PasswordSeedColumns.PASSWORD_SEED);
                if (idColumn != -1) {
                    passwordSeed = new PasswordSeed(c.getString(idColumn));
                }
            }
            c.close();
        } catch (SQLException e) {
        }
        return passwordSeed;
    }

    @Override
    public boolean hasPasswordSeed() {
        boolean result = false;
        try {
            result = hasPasswordSeed(this.mDb.getConn());
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    private boolean hasPasswordSeed(Connection conn) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement("select  count(0) cnt from password_seed  where  password_seed is not null ");
        ResultSet c = stmt.executeQuery();
        int count = 0;
        try {
            if (c.next()) {
                int idColumn = c.findColumn("cnt");
                if (idColumn != -1) {
                    count = c.getInt(idColumn);
                }
            }
            c.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return count > 0;

    }

    @Override
    public List<Integer> getHDSeeds() {
        List<Integer> hdSeedIds = new ArrayList<Integer>();
        try {
            String sql = "select hd_seed_id from hd_seeds";
            ResultSet c = this.mDb.query(sql, null);

            while (c.next()) {
                int idColumn = c.findColumn(AbstractDb.HDSeedsColumns.HD_SEED_ID);
                if (idColumn != 0) {
                    hdSeedIds.add(c.getInt(idColumn));
                }
            }
            c.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {

        }
        return hdSeedIds;
    }

    @Override
    public String getEncryptSeed(int hdSeedId) {
        String encryptSeed = null;

        try {
            String sql = "select encrypt_seed from hd_seeds where hd_seed_id=?";
            ResultSet c = this.mDb.query(sql, new String[]{Integer.toString(hdSeedId)});
            if (c.next()) {
                int idColumn = c.findColumn(AbstractDb.HDSeedsColumns.ENCRYPT_SEED);
                if (idColumn != -1) {
                    encryptSeed = c.getString(idColumn);
                }
            }
            c.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return encryptSeed;
    }

    @Override
    public String getEncryptHDSeed(int hdSeedId) {
        String encryptHDSeed = null;

        try {
            String sql = "select encrypt_hd_seed from hd_seeds where hd_seed_id=?";
            ResultSet c = this.mDb.query(sql, new String[]{Integer.toString(hdSeedId)});
            if (c.next()) {
                int idColumn = c.findColumn(AbstractDb.HDSeedsColumns.ENCRYPT_HD_SEED);
                if (idColumn != -1) {
                    encryptHDSeed = c.getString(idColumn);
                }
            }
            c.close();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return encryptHDSeed;
    }

    @Override
    public void updateEncryptHDSeed(int hdSeedId, String encryptHDSeed) {
        this.mDb.executeUpdate("update hd_seeds set encrypt_HD_seed=? where hd_seed_id=?",
                new String[]{encryptHDSeed, Integer.toString(hdSeedId)});
    }


    @Override
    public boolean isHDSeedFromXRandom(int hdSeedId) {
        boolean isXRandom = false;
        try {
            ResultSet cursor = this.mDb.query("select is_xrandom from hd_seeds where hd_seed_id=?"
                    , new String[]{Integer.toString(hdSeedId)});
            if (cursor.next()) {
                int idColumn = cursor.findColumn(AbstractDb.HDSeedsColumns.IS_XRANDOM);
                if (idColumn != -1) {
                    isXRandom = cursor.getInt(idColumn) == 1;
                }
            }
            cursor.close();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return isXRandom;
    }


    @Override
    public String getHDMFristAddress(int hdSeedId) {
        String address = null;
        try {
            ResultSet cursor = this.mDb.query("select hdm_address from hd_seeds where hd_seed_id=?"
                    , new String[]{Integer.toString(hdSeedId)});

            if (cursor.next()) {
                int idColumn = cursor.findColumn(AbstractDb.HDSeedsColumns.HDM_ADDRESS);
                if (idColumn != -1) {
                    address = cursor.getString(idColumn);
                }
            }
            cursor.close();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return address;
    }

    @Override
    public int addHDKey(final String encryptSeed, final String encryptHdSeed, final String firstAddress, final boolean isXrandom, final String addressOfPS) {
        int result = 0;
        try {
            this.mDb.getConn().setAutoCommit(false);
            String[] params = new String[]{encryptSeed, encryptHdSeed, Integer.toString(isXrandom ? 1 : 0), firstAddress};
            PreparedStatement stmt = this.mDb.getConn().prepareStatement(insertHDSeedSql);
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    stmt.setString(i + 1, params[i]);
                }
            }
            stmt.executeUpdate();
            if (!hasPasswordSeed(this.mDb.getConn()) && !Utils.isEmpty(addressOfPS)) {
                addPasswordSeed(this.mDb.getConn(), new PasswordSeed(addressOfPS, encryptSeed));
            }
            this.mDb.getConn().commit();
            ResultSet cursor = this.mDb.query("select hd_seed_id from hd_seeds where encrypt_seed=? and encrypt_HD_seed=? and is_xrandom=? and hdm_address=?"
                    , new String[]{encryptSeed, encryptHdSeed, Integer.toString(isXrandom ? 1 : 0), firstAddress});


            if (cursor.next()) {
                int idColumn = cursor.findColumn(AbstractDb.HDSeedsColumns.HD_SEED_ID);
                if (idColumn != -1) {
                    result = cursor.getInt(idColumn);
                }

            }
            cursor.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public HDMBId getHDMBId() {
        HDMBId hdmbId = null;
        ResultSet c = null;
        String address = null;
        String encryptBitherPassword = null;
        try {

            String sql = "select " + AbstractDb.HDMBIdColumns.HDM_BID + "," + AbstractDb.HDMBIdColumns.ENCRYPT_BITHER_PASSWORD + " from " +
                    AbstractDb.Tables.HDM_BID;
            c = this.mDb.query(sql, null);
            if (c.next()) {
                int idColumn = c.findColumn(AbstractDb.HDMBIdColumns.HDM_BID);
                if (idColumn != -1) {
                    address = c.getString(idColumn);
                }
                idColumn = c.findColumn(AbstractDb.HDMBIdColumns.ENCRYPT_BITHER_PASSWORD);
                if (idColumn != -1) {
                    encryptBitherPassword = c.getString(idColumn);
                }

            }
            if (!Utils.isEmpty(address) && !Utils.isEmpty(encryptBitherPassword)) {
                hdmbId = new HDMBId(address, encryptBitherPassword);
            }
            c.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return hdmbId;
    }

    @Override
    public void addHDMBId(final HDMBId bitherId, final String addressOfPS) {

        boolean isExist = true;

        try {
            String sql = "select count(0) cnt from " + AbstractDb.Tables.HDM_BID;
            ResultSet c = this.mDb.query(sql, null);
            if (c.next()) {
                int idColumn = c.findColumn("cnt");
                isExist = c.getInt(idColumn) > 0;
            }

            c.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        if (!isExist) {
            try {
                this.mDb.getConn().setAutoCommit(false);
                String encryptedBitherPasswordString = bitherId.getEncryptedBitherPasswordString();
                PreparedStatement stmt = this.mDb.getConn().prepareStatement(insertHDMBidSql);
                stmt.setString(1, bitherId.getAddress());
                stmt.setString(2, encryptedBitherPasswordString);
                if (!hasPasswordSeed(this.mDb.getConn()) && !Utils.isEmpty(addressOfPS)) {
                    addPasswordSeed(this.mDb.getConn(), new PasswordSeed(addressOfPS, encryptedBitherPasswordString));
                }
                this.mDb.getConn().commit();

            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public List<HDMAddress> getHDMAddressInUse(HDMKeychain keychain) {
        List<HDMAddress> addresses = new ArrayList<HDMAddress>();

        try {
            ResultSet c = null;

            String sql = "select hd_seed_index,pub_key_hot,pub_key_cold,pub_key_remote,address,is_synced " +
                    "from hdm_addresses " +
                    "where hd_seed_id=? and address is not null order by hd_seed_index";
            c = this.mDb.query(sql, new String[]{Integer.toString(keychain.getHdSeedId())});
            while (c.next()) {
                HDMAddress hdmAddress = applyHDMAddress(c, keychain);
                if (hdmAddress != null) {
                    addresses.add(hdmAddress);
                }
            }
            c.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return addresses;
    }


    @Override
    public void prepareHDMAddresses(final int hdSeedId, final List<HDMAddress.Pubs> pubsList) {


        boolean isExist = false;
        try {
            for (HDMAddress.Pubs pubs : pubsList) {
                String sql = "select count(0) cnt from hdm_addresses where hd_seed_id=? and hd_seed_index=?";
                PreparedStatement stmt = this.mDb.getConn().prepareStatement(sql);
                stmt.setString(1, Integer.toString(hdSeedId));
                stmt.setString(2, Integer.toString(pubs.index));
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    int idColumn = rs.findColumn("cnt");
                    if (idColumn != -1) {
                        isExist |= rs.getInt(idColumn) > 0;
                    }
                }
                rs.close();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            isExist = true;
        }
        try {
            if (!isExist) {
                this.mDb.getConn().setAutoCommit(false);
                for (int i = 0; i < pubsList.size(); i++) {
                    HDMAddress.Pubs pubs = pubsList.get(i);
                    applyHDMAddressContentValues(this.mDb.getConn(), null, hdSeedId, pubs.index, pubs.hot, pubs.cold, null, false);

                }
                this.mDb.getConn().commit();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }

    @Override
    public List<HDMAddress.Pubs> getUncompletedHDMAddressPubs(int hdSeedId, int count) {

        List<HDMAddress.Pubs> pubsList = new ArrayList<HDMAddress.Pubs>();
        try {
            ResultSet cursor = this.mDb.query("select * from hdm_addresses where hd_seed_id=? and pub_key_remote is null limit ? ", new String[]{
                    Integer.toString(hdSeedId), Integer.toString(count)
            });
            try {
                while (cursor.next()) {
                    HDMAddress.Pubs pubs = applyPubs(cursor);
                    if (pubs != null) {
                        pubsList.add(pubs);
                    }
                }
            } catch (AddressFormatException e) {
                e.printStackTrace();
            }

            cursor.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return pubsList;
    }

    @Override
    public int maxHDMAddressPubIndex(int hdSeedId) {
        ResultSet cursor = this.mDb.query("select ifnull(max(hd_seed_index),-1)  hd_seed_index from hdm_addresses where hd_seed_id=?  ", new String[]{
                Integer.toString(hdSeedId)
        });
        int maxIndex = -1;
        try {


            if (cursor.next()) {
                int idColumn = cursor.findColumn(AbstractDb.HDMAddressesColumns.HD_SEED_INDEX);
                if (idColumn != -1) {
                    maxIndex = cursor.getInt(idColumn);
                }
            }
            cursor.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return maxIndex;
    }

    @Override
    public int uncompletedHDMAddressCount(int hdSeedId) {
        ResultSet cursor = this.mDb.query("select count(0) cnt from hdm_addresses where hd_seed_id=?  and pub_key_remote is null "
                , new String[]{
                Integer.toString(hdSeedId)
        });
        int count = 0;
        try {
            if (cursor.next()) {
                int idColumn = cursor.findColumn("cnt");
                if (idColumn != -1) {
                    count = cursor.getInt(idColumn);
                }
            }
            cursor.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return count;


    }


    @Override
    public void completeHDMAddresses(final int hdSeedId, final List<HDMAddress> addresses) {
        try {
            boolean isExist = true;
            ResultSet c = null;
            try {
                for (HDMAddress address : addresses) {

                    String sql = "select count(0) cnt from hdm_addresses " +
                            "where hd_seed_id=? and hd_seed_index=? and address is null";
                    PreparedStatement stmt = this.mDb.getConn().prepareStatement(sql);
                    stmt.setString(1, Integer.toString(hdSeedId));
                    stmt.setString(2, Integer.toString(address.getIndex()));
                    c = stmt.executeQuery();
                    if (c.next()) {
                        int idColumn = c.findColumn("cnt");
                        if (idColumn != -1) {
                            isExist &= c.getInt(0) > 0;
                        }
                    }
                    c.close();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                isExist = false;
            } finally {
                if (c != null && !c.isClosed())
                    c.close();
            }
            if (isExist) {
                this.mDb.getConn().setAutoCommit(false);
                for (int i = 0; i < addresses.size(); i++) {
                    HDMAddress address = addresses.get(i);
                    String sql = "update hdm_addresses set pub_key_remote=?,address=? where hd_seed_id=? and hd_seed_index=?";
                    PreparedStatement stmt = this.mDb.getConn().prepareStatement(sql);
                    stmt.setString(1, Base58.encode(address.getPubRemote()));
                    stmt.setString(2, address.getAddress());
                    stmt.setString(3, Integer.toString(hdSeedId));
                    stmt.setString(4, Integer.toString(address.getIndex()));
                    stmt.executeUpdate();
                }
                this.mDb.getConn().commit();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }

    public void setHDMPubsRemote(final int hdSeedId, final int index, final byte[] remote) {
        try {
            boolean isExist = true;
            ResultSet c = null;
            try {
                String sql = "select count(0) cnt from hdm_addresses " +
                        "where hd_seed_id=? and hd_seed_index=? and address is null";
                PreparedStatement stmt = this.mDb.getConn().prepareStatement(sql);
                stmt.setString(1, Integer.toString(hdSeedId));

                c = stmt.executeQuery();
                if (c.next()) {
                    int idColumn = c.findColumn("cnt");
                    if (idColumn != -1) {
                        isExist &= c.getInt(0) > 0;
                    }
                }
                c.close();

            } catch (Exception ex) {
                ex.printStackTrace();
                isExist = false;
            } finally {
                if (c != null && !c.isClosed())
                    c.close();
            }
            if (isExist) {
                String sql = "update hdm_addresses set pub_key_remote=? where hd_seed_id=? and hd_seed_index=?";
                PreparedStatement stmt = this.mDb.getConn().prepareStatement(sql);
                stmt.setString(1, Base58.encode(remote));
                stmt.setString(2, Integer.toString(hdSeedId));
                stmt.setString(3, Integer.toString(index));
                stmt.executeUpdate();
            }
            this.mDb.getConn().commit();
        } catch (SQLException e) {
            e.printStackTrace();
        }


    }

    private static final String insertHDMAddressSql = "insert into hdm_addresses " +
            "(hd_seed_id,hd_seed_index,pub_key_hot,pub_key_cold,pub_key_remote,address,is_synced)" +
            " values (?,?,?,?,?,?,?) ";

    @Override
    public void recoverHDMAddresses(final int hdSeedId, final List<HDMAddress> addresses) {
        try {
            this.mDb.getConn().setAutoCommit(false);
            for (int i = 0; i < addresses.size(); i++) {
                HDMAddress address = addresses.get(i);
                applyHDMAddressContentValues(this.mDb.getConn(), address.getAddress(), hdSeedId,
                        address.getIndex(), address.getPubHot(), address.getPubCold(), address.getPubRemote(), false);


            }
            this.mDb.getConn().commit();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void syncComplete(int hdSeedId, int hdSeedIndex) {
        this.mDb.executeUpdate("update addresses set is_synced=? where hd_seed_id=? and hd_seed_index=?"
                , new String[]{Integer.toString(1), Integer.toString(hdSeedId), Integer.toString(hdSeedIndex)});
    }

    //normal
    @Override
    public List<Address> getAddresses() {
        List<Address> addressList = new ArrayList<Address>();
        try {
            ResultSet c = this.mDb.query("select address,encrypt_private_key,pub_key,is_xrandom,is_trash,is_synced,sort_time " +
                    "from addresses  order by sort_time desc", null);

            while (c.next()) {
                Address address = null;
                try {
                    address = applyAddressCursor(c);
                } catch (AddressFormatException e) {
                    e.printStackTrace();
                }
                if (address != null) {
                    addressList.add(address);
                }
            }
            c.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return addressList;
    }

    @Override
    public String getEncryptPrivateKey(String address) {
        String encryptPrivateKey = null;
        try {
            ResultSet c = this.mDb.query("select encrypt_private_key from addresses  where address=?", new String[]{address});
            if (c.next()) {
                int idColumn = c.findColumn(AbstractDb.AddressesColumns.ENCRYPT_PRIVATE_KEY);
                if (idColumn != -1) {
                    encryptPrivateKey = c.getString(idColumn);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return encryptPrivateKey;
    }


    @Override
    public void addAddress(final Address address) {
        try {

            this.mDb.getConn().setAutoCommit(false);
            String[] params = new String[]{address.getAddress(), address.hasPrivKey() ? address.getEncryptPrivKeyOfDb() : null, Base58.encode(address.getPubKey()),
                    Integer.toString(address.isFromXRandom() ? 1 : 0), Integer.toString(address.isSyncComplete() ? 1 : 0), Integer.toString(address.isTrashed() ? 1 : 0), Long.toString(address.getSortTime())};
            PreparedStatement stmt = this.mDb.getConn().prepareStatement(insertAddressSql);
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    stmt.setString(i + 1, params[i]);
                }
            }
            stmt.executeUpdate();

            if (address.hasPrivKey()) {
                if (!hasPasswordSeed(this.mDb.getConn())) {
                    PasswordSeed passwordSeed = new PasswordSeed(address.getAddress(), address.getFullEncryptPrivKeyOfDb());
                    addPasswordSeed(this.mDb.getConn(), passwordSeed);
                }
            }
            this.mDb.getConn().commit();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void updatePrivateKey(String address, String encryptPriv) {
        this.mDb.executeUpdate("update addresses set encrypt_private_key=? where address=?"
                , new String[]{encryptPriv, address});

    }

    @Override
    public void removeWatchOnlyAddress(Address address) {
        this.mDb.executeUpdate("delete from addresses where address=? and encrypt_private_key is null ",
                new String[]{address.getAddress()});

    }


    @Override
    public void trashPrivKeyAddress(Address address) {
        this.mDb.executeUpdate("update addresses set is_trash=1 where address=?"
                , new String[]{address.getAddress()});
    }

    @Override
    public void restorePrivKeyAddress(Address address) {
        this.mDb.executeUpdate("update addresses set is_trash=0 ,is_synced=0,sort_time=? where address=?"
                , new String[]{Long.toString(address.getSortTime()), address.getAddress()});
    }

    @Override
    public void updateSyncComplete(Address address) {

        this.mDb.executeUpdate("update addresses set is_synced=? where address=?"
                , new String[]{Integer.toString(address.isSyncComplete() ? 1 : 0), address.getAddress()});

    }

    public void addPasswordSeed(Connection conn, PasswordSeed passwordSeed) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement("insert into password_seed (password_seed)  values (?)");
        stmt.setString(1, passwordSeed.toPasswordSeedString());
        stmt.executeUpdate();
    }


    private HDMAddress applyHDMAddress(ResultSet c, HDMKeychain keychain) throws AddressFormatException, SQLException

    {
        HDMAddress hdmAddress;

        String address = null;
        boolean isSynced = false;

        int idColumn = c.findColumn(AbstractDb.HDMAddressesColumns.ADDRESS);
        if (idColumn != -1) {
            address = c.getString(idColumn);
        }
        idColumn = c.findColumn(AbstractDb.HDMAddressesColumns.IS_SYNCED);
        if (idColumn != -1) {
            isSynced = c.getInt(idColumn) == 1;
        }
        HDMAddress.Pubs pubs = applyPubs(c);
        hdmAddress = new HDMAddress(pubs, address, isSynced, keychain);
        return hdmAddress;

    }

    private HDMAddress.Pubs applyPubs(ResultSet c) throws AddressFormatException, SQLException {
        int hdSeedIndex = 0;
        byte[] hot = null;
        byte[] cold = null;
        byte[] remote = null;
        int idColumn = c.findColumn(AbstractDb.HDMAddressesColumns.HD_SEED_INDEX);
        if (idColumn != -1) {
            hdSeedIndex = c.getInt(idColumn);
        }
        idColumn = c.findColumn(AbstractDb.HDMAddressesColumns.PUB_KEY_HOT);
        if (idColumn != -1) {
            String str = c.getString(idColumn);
            if (!Utils.isEmpty(str)) {
                hot = Base58.decode(str);
            }
        }
        idColumn = c.findColumn(AbstractDb.HDMAddressesColumns.PUB_KEY_COLD);
        if (idColumn != -1) {
            String str = c.getString(idColumn);
            if (!Utils.isEmpty(str)) {
                cold = Base58.decode(str);
            }

        }
        idColumn = c.findColumn(AbstractDb.HDMAddressesColumns.PUB_KEY_REMOTE);
        if (idColumn != -1) {
            String str = c.getString(idColumn);
            if (!Utils.isEmpty(str)) {
                remote = Base58.decode(str);
            }

        }
        HDMAddress.Pubs pubs = new HDMAddress.Pubs(hot, cold, remote, hdSeedIndex);
        return pubs;

    }

    private Address applyAddressCursor(ResultSet c) throws AddressFormatException, SQLException {
        Address address;
        int idColumn = c.findColumn(AbstractDb.AddressesColumns.ADDRESS);
        String addressStr = null;
        String encryptPrivateKey = null;
        byte[] pubKey = null;
        boolean isXRandom = false;
        boolean isSynced = false;
        boolean isTrash = false;
        long sortTime = 0;

        if (idColumn != -1) {
            addressStr = c.getString(idColumn);
            if (!Utils.validBicoinAddress(addressStr)) {
                return null;
            }
        }
        idColumn = c.findColumn(AbstractDb.AddressesColumns.ENCRYPT_PRIVATE_KEY);
        if (idColumn != -1) {
            encryptPrivateKey = c.getString(idColumn);
        }
        idColumn = c.findColumn(AbstractDb.AddressesColumns.PUB_KEY);
        if (idColumn != -1) {
            pubKey = Base58.decode(c.getString(idColumn));
        }
        idColumn = c.findColumn(AbstractDb.AddressesColumns.IS_XRANDOM);
        if (idColumn != -1) {
            isXRandom = c.getInt(idColumn) == 1;
        }
        idColumn = c.findColumn(AbstractDb.AddressesColumns.IS_SYNCED);
        if (idColumn != -1) {
            isSynced = c.getInt(idColumn) == 1;
        }
        idColumn = c.findColumn(AbstractDb.AddressesColumns.IS_TRASH);
        if (idColumn != -1) {
            isTrash = c.getInt(idColumn) == 1;
        }
        idColumn = c.findColumn(AbstractDb.AddressesColumns.SORT_TIME);
        if (idColumn != -1) {
            sortTime = c.getLong(idColumn);
        }
        address = new Address(addressStr, pubKey, sortTime, isSynced, isXRandom, isTrash, encryptPrivateKey);

        return address;
    }

    private void applyHDMAddressContentValues(Connection conn, String address, int hdSeedId, int index, byte[] pubKeysHot,
                                              byte[] pubKeysCold, byte[] pubKeysRemote, boolean isSynced) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(insertHDMAddressSql);
        stmt.setString(1, Integer.toString(hdSeedId));
        stmt.setString(2, Integer.toString(index));
        stmt.setString(3, Base58.encode(pubKeysHot));
        stmt.setString(4, Base58.encode(pubKeysCold));
        stmt.setString(5, pubKeysRemote == null ? null : Base58.encode(pubKeysRemote));
        stmt.setString(6, Utils.isEmpty(address) ? null : address);
        stmt.setString(7, Integer.toString(isSynced ? 1 : 0));
        stmt.executeUpdate();
    }
}
