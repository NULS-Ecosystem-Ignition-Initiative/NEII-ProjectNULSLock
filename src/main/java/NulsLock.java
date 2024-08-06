import ch.qos.logback.core.util.COWArrayList;
import com.fasterxml.jackson.databind.BeanProperty;
import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.annotation.*;
import io.nuls.contract.sdk.event.DebugEvent;
import org.checkerframework.checker.units.qual.A;

import javax.validation.constraints.Min;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;


/**
* @notice Nuls Contract that locks the nuls deposited, returns
* yield to aproject during an x period of time and
* returns nuls locked in the end of the period
*
* @dev Nuls are deposited in AINULS in order to receive yield
*
* Developed by Pedro G. S. Ferreira @Pedro_Ferreir_a
* */
public class NulsLock implements Contract{

    //Contract where NULS are deposited
    public Address aiNULSDepositContract;

    //AINULS Token Contract
    public Address aiNULS;

    //Time the NULS will be locked
    public BigInteger lockTime;

    // USE uint256 instead of bool to save gas
    // paused = 1 && active = 2
    public int paused = 2;

    public Address manager;

    //User Balance
    public Map<Address, BigInteger> userBalance  = new HashMap<>();
    public Map<Address, BigInteger> userLockTime = new HashMap<>();
    public Map<Address, Boolean> projectAdmin = new HashMap<>();

    //--------------------------------------------------------------------
    //Initialize Contract
    public NulsLock(Address aiNULSDepositContract_, Address aiNULS_, BigInteger lockTime_, Address admin_) {

        aiNULSDepositContract_  = aiNULSDepositContract_;
        aiNULS                  = aiNULS_;
        lockTime                = lockTime_;
        projectAdmin.put(admin_, true);


    }

    @View
    public Address getUserBalance(Address addr){
        if(userBalance.get(addr) == null)
            return BigInteger.ZERO;
        return userBalance.get(addr);
    }

    /** Essential to receive funds back from aiNULS
     *
     * @dev DON'T REMOVE IT
     */
    @Payable
    public void _payable() {

    }

    /**
     * Deposit funds on Lock
     *
     * */
    @Payable
    public void deposit(BigInteger amount) {

        require(Msg.value().compareTo(amount)  >= 0, "Invalid Amount sent");

        if(userBalance.get(Msg.sender()) == null){
            userBalance.put(Msg.sender(), amount);
            //Add 2 years lock
            userLockTime.put(Msg.sender(), Block.timestamp() +  (2 * 365 * 24 * 60 * 60));
        }else{
            userBalance.put(Msg.sender(), userBalance.get(Msg.sender()).add(amount));
            //Add 2 years lock
            userLockTime.put(Msg.sender(), Block.timestamp() +  (2 * 365 * 24 * 60 * 60));
        }

        String[][] args = new String[][]{new String[]{from.toString()}, new String[]{recipient.toString()}, new String[]{amount.toString()}};
        aiNULSDepositContract.callWithReturnValue("deposit", "", args, amount);

    }

    public void claimRewards(){

        require(projectAdmin.get(Msg.sender()) != null && projectAdmin.get(Msg.sender()), "Invalid Admin");

        String[][] args = new String[][]{new String[]{from.toString()}, new String[]{recipient.toString()}, new String[]{amount.toString()}};
        aiNULSDepositContract.callWithReturnValue("withdraw", "", args, BigInteger.ZERO);


    }

    public void withdrawAfterLock(){

        require(projectAdmin.get(Msg.sender()) != null && projectAdmin.get(Msg.sender()), "Invalid Admin");

        String[][] args = new String[][]{new String[]{from.toString()}, new String[]{recipient.toString()}, new String[]{amount.toString()}};
        aiNULSDepositContract.callWithReturnValue("withdraw", "", args, BigInteger.ZERO);


    }

    public void addAdmin(Address newAdmin){

        require(projectAdmin.get(Msg.sender()) != null && projectAdmin.get(Msg.sender()), "Invalid Admin");

        projectAdmin.put(newAdmin, true);

    }

    public void removeAdmin(Address removeAdmin){

        require(projectAdmin.get(Msg.sender()) != null && projectAdmin.get(Msg.sender()), "Invalid Admin");
        require(!Msg.sender().equals(removeAdmin), "Can't remove itself");

        projectAdmin.put(removeAdmin, false);

    }


    //--------------------------------------------------------------------
    /** FUNCTIONS */

    /**
     * Get Manager
     *
     * @return contract manager
     */
    @View
    public Boolean isAdmin(Address admin) {
        if(projectAdmin.get(admin) == null)
            return false;
        return projectAdmin.get(admin);
    }

    /**
     * Get aiNULS Deposit Contract address
     *
     * @return All ALL
     */
    @View
    public Address getAINULSDepositCtrAddr() {
        return aiNULSDepositContract;
    }

    /**
     * Get aiNULS asset address
     *
     * @return All ALL
     */
    @View
    public Address getAINULSContractAddr() {
        return aiNULS;
    }








    private BigInteger getBalAINULS(@Required Address token, @Required Address owner){
        String[][] args = new String[][]{new String[]{owner.toString()}};
        BigInteger b = new BigInteger(token.callWithReturnValue("balanceOf", "", args, BigInteger.ZERO));
        return b;
    }

    private BigInteger getBalInContract(@Required Address token, @Required Address owner){
        String[][] args = new String[][]{new String[]{owner.toString()}};
        BigInteger b = new BigInteger(token.callWithReturnValue("_balanceOf", "", args, BigInteger.ZERO));
        return b;
    }






    //--------------------------------------------------------------------
    /** INTERNAL FUNCTIONS */


    private void transferERC20(
            Address _token,
            Address _from,
            Address _to,
            BigInteger _amount
    ){

        if (_from.equals(Msg.address())) {
            safeTransfer(_token,  _to, _amount);
        } else {
            safeTransferFrom(_token, _from, _to, _amount);
        }
    }

    //--------------------------------------------------------------------
    /** OWNER FUNCTIONS */


    private BigInteger safeBalanceOf(@Required Address token, @Required Address recipient){
        String[][] argsM = new String[][]{new String[]{recipient.toString()}};
        BigInteger b = new BigInteger(token.callWithReturnValue("balanceOf", "", argsM, BigInteger.ZERO));
        return b;
    }

    private void safeTransfer(@Required Address token, @Required Address recipient, @Required BigInteger amount){
        String[][] argsM = new String[][]{new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transfer", "", argsM, BigInteger.ZERO));
        require(b, "NulswapLendingV1: Failed to transfer");
    }
    /**
     * SafeTransfer a token asset from an user to another recipient
     *
     * */
    private void safeTransferFrom(@Required Address token, @Required Address from, @Required Address recipient, @Required BigInteger amount){
        String[][] args = new String[][]{new String[]{from.toString()}, new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transferFrom", "", args, BigInteger.ZERO));
        require(b, "NulswapLendingV1: Failed to transfer");
    }

}